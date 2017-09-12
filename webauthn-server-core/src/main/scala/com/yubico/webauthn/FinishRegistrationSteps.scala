package com.yubico.webauthn

import java.util.Optional

import com.yubico.scala.util.JavaConverters._
import com.yubico.u2f.attestation.MetadataResolver
import com.yubico.u2f.attestation.MetadataObject
import com.yubico.u2f.crypto.Crypto
import com.yubico.u2f.data.messages.key.util.U2fB64Encoding
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.MakePublicKeyCredentialOptions
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.AuthenticatorAttestationResponse
import com.yubico.webauthn.data.Base64UrlString
import com.yubico.webauthn.data.CollectedClientData
import com.yubico.webauthn.data.PublicKeyCredentialType
import com.yubico.webauthn.data.AttestationObject
import com.yubico.webauthn.data.ArrayBuffer
import com.yubico.webauthn.data.AttestationType
import com.yubico.webauthn.data.Basic
import com.yubico.webauthn.data.SelfAttestation
import com.yubico.webauthn.impl.FidoU2fAttestationStatementVerifier
import com.yubico.webauthn.impl.AttestationTrustResolver
import com.yubico.webauthn.impl.FidoU2fAttestationTrustResolver

import scala.collection.JavaConverters._
import scala.util.Try

sealed trait Step[A <: Step[_]] {
  protected def isFinished: Boolean = false
  protected def nextStep: A
  protected def result: Option[RegistrationResult] = None
  protected def validate(): Unit

  private[webauthn] def next: Try[A] = validations map { _ => nextStep }
  private[webauthn] def validations: Try[Unit] = Try { validate() }

  def run: Try[RegistrationResult] =
    if (isFinished) Try(result.get)
    else next flatMap { _.run }
}

case class FinishRegistrationSteps(
  request: MakePublicKeyCredentialOptions,
  response: PublicKeyCredential[AuthenticatorAttestationResponse],
  callerTokenBindingId: Optional[Base64UrlString],
  origin: String,
  rpId: String,
  crypto: Crypto,
  allowSelfAttestation: Boolean,
  metadataResolver: Optional[MetadataResolver],
) {

  private[webauthn] def begin: Step1 = Step1()
  def run: Try[RegistrationResult] = begin.run

  case class Step1 private () extends Step[Step2] {
    override def validate() = assert(clientData != null, "Client data must not be null.")
    override def nextStep = Step2()
    def clientData: CollectedClientData = response.response.collectedClientData
  }

  case class Step2 private () extends Step[Step3] {
    override def validate() {
      assert(
        U2fB64Encoding.decode(response.response.collectedClientData.challenge).toVector == request.challenge,
        "Incorrect challenge."
      )
    }
    override def nextStep = Step3()
  }

  case class Step3 private () extends Step[Step4] {
    override def validate() {
      assert(
        response.response.collectedClientData.origin == origin,
        "Incorrect origin."
      )
    }
    override def nextStep = Step4()
  }

  case class Step4 private () extends Step[Step5] {
    override def validate() =
      (callerTokenBindingId.asScala, response.response.collectedClientData.tokenBindingId.asScala) match {
        case (None, None) =>
        case (_, None) => throw new AssertionError("Token binding ID set by caller but not in attestation message.")
        case (None, _) => throw new AssertionError("Token binding ID set in attestation message but not by caller.")
        case (Some(callerToken), Some(responseToken)) =>
          assert(callerToken == responseToken, "Incorrect token binding ID.")
      }
    def nextStep = Step5()
  }

  case class Step5 private () extends Step[Step6] {
    override def validate() {
      response.response.collectedClientData.clientExtensions.asScala foreach { extensions =>
        assert(
          request.extensions.isPresent,
          "Extensions were returned, but not requested."
        )

        assert(
          extensions.fieldNames.asScala.toSet subsetOf request.extensions.get.fieldNames.asScala.toSet,
          "Client extensions are not a subset of requested extensions."
        )
      }

      response.response.collectedClientData.authenticatorExtensions.asScala foreach { extensions =>
        assert(
          request.extensions.isPresent,
          "Extensions were returned, but not requested."
        )

        assert(
          extensions.fieldNames.asScala.toSet subsetOf request.extensions.get.fieldNames.asScala.toSet,
          "Authenticator extensions are not a subset of requested extensions."
        )
      }
    }
    override def nextStep = Step6()
  }

  case class Step6 private () extends Step[Step7] {
    val supportedHashAlgorithms: List[String] = List("SHA-256")

    override def validate() {
      val hashAlgorithm: String = response.response.collectedClientData.hashAlgorithm.toLowerCase
      assert(
        supportedHashAlgorithms map { _.toLowerCase } contains hashAlgorithm,
        s"Forbidden hash algorithm: ${hashAlgorithm}"
      )
    }
    override def nextStep = Step7(clientDataJsonHash)

    def clientDataJsonHash: ArrayBuffer = crypto.hash(response.response.clientDataJSON.toArray).toVector
  }
  case class Step7 private (clientDataJsonHash: ArrayBuffer) extends Step[Step8] {
    override def validate() {
      assert(attestation != null, "Malformed attestation object.")
    }
    override def nextStep = Step8(clientDataJsonHash, attestation)

    def attestation: AttestationObject = response.response.attestation
  }

  case class Step8 private (clientDataJsonHash: ArrayBuffer, attestation: AttestationObject) extends Step[Step9] {
    override def validate() {
      assert(
        response.response.attestation.authenticatorData.rpIdHash == crypto.hash(rpId).toVector,
        "Wrong RP ID hash."
      )
    }
    override def nextStep = Step9(clientDataJsonHash, attestation)
  }

  case class Step9 private (clientDataJsonHash: ArrayBuffer, attestation: AttestationObject) extends Step[Step10] {
    override def validate(): Unit = {
      assert(formatSupported, s"Unsupported attestation statement format: ${format}")
    }
    override def nextStep = Step10(clientDataJsonHash, attestation, attestationStatementVerifier)

    def format: String = attestation.format
    def formatSupported: Boolean = format == "fido-u2f"
    def attestationStatementVerifier: AttestationStatementVerifier = format match {
      case "fido-u2f" => FidoU2fAttestationStatementVerifier
    }
  }

  case class Step10 (clientDataJsonHash: ArrayBuffer, attestation: AttestationObject, attestationStatementVerifier: AttestationStatementVerifier) extends Step[Step11] {
    override def validate() {
      assert(
        attestationStatementVerifier.verifyAttestationSignature(attestation, clientDataJsonHash),
        "Invalid attestation signature."
      )
    }
    override def nextStep = Step11(
      attestation = attestation,
      attestationType = attestationType,
      attestationStatementVerifier = attestationStatementVerifier,
    )

    def attestationType: AttestationType = attestationStatementVerifier.getAttestationType(attestation)
  }

  case class Step11 private (
    private val attestation: AttestationObject,
    private val attestationType: AttestationType,
    private val attestationStatementVerifier: AttestationStatementVerifier,
  ) extends Step[Step12] {
    override def validate() {
      assert(attestationType == SelfAttestation || trustResolver.isPresent, "Failed to obtain attestation trust anchors.")
    }
    override def nextStep = Step12(
      attestation = attestation,
      attestationType = attestationType,
      trustResolver = trustResolver,
    )

    def trustResolver: Optional[AttestationTrustResolver] = (attestationType match {
      case SelfAttestation => None
      case Basic =>
        attestation.format match {
          case "fido-u2f" => Try(new FidoU2fAttestationTrustResolver(metadataResolver.get)).toOption
        }
      case _ => ???
    }).asJava
  }

  case class Step12 private (
    attestation: AttestationObject,
    attestationType: AttestationType,
    trustResolver: Optional[AttestationTrustResolver],
  ) extends Step[Finished] {
    override def validate() {
      attestationType match {
        case SelfAttestation =>
          assert(allowSelfAttestation, "Self attestation is not allowed.")

        case Basic =>
          assert(attestationMetadata.isPresent, "Failed to derive trust for attestation key.")

        case _ => ???
      }
    }
    override def nextStep = Finished(
      attestationTrusted = attestationTrusted,
      attestationMetadata = attestationMetadata,
    )

    def attestationTrusted: Boolean = {
      attestationType match {
        case SelfAttestation => allowSelfAttestation
        case Basic => attestationMetadata.isPresent
        case _ => ???
      }
    }
    def attestationMetadata: Optional[MetadataObject] = trustResolver.asScala.flatMap(_.resolveTrustAnchor(attestation).asScala).asJava
  }

  case class Finished private (
    attestationMetadata: Optional[MetadataObject],
    attestationTrusted: Boolean,
  ) extends Step[Finished] {
    override def validate() { /* No-op */ }
    override def isFinished = true
    override def nextStep = this

    override def result: Option[RegistrationResult] = Some(RegistrationResult(
      keyId = keyId,
      attestationTrusted = attestationTrusted,
      attestationMetadata = attestationMetadata,
    ))

    def keyId: PublicKeyCredentialDescriptor = PublicKeyCredentialDescriptor(
      `type` = PublicKeyCredentialType(response.`type`).get,
      id = response.rawId,
    )
  }

}