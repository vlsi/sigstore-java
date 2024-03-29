/*
 * Copyright 2022 The Sigstore Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.sigstore;

import com.google.api.client.util.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import dev.sigstore.encryption.certificates.Certificates;
import dev.sigstore.encryption.signers.Signer;
import dev.sigstore.encryption.signers.Signers;
import dev.sigstore.fulcio.client.CertificateRequest;
import dev.sigstore.fulcio.client.FulcioClient;
import dev.sigstore.fulcio.client.FulcioVerificationException;
import dev.sigstore.fulcio.client.FulcioVerifier;
import dev.sigstore.fulcio.client.UnsupportedAlgorithmException;
import dev.sigstore.oidc.client.OidcClients;
import dev.sigstore.oidc.client.OidcException;
import dev.sigstore.oidc.client.OidcToken;
import dev.sigstore.rekor.client.HashedRekordRequest;
import dev.sigstore.rekor.client.RekorClient;
import dev.sigstore.rekor.client.RekorParseException;
import dev.sigstore.rekor.client.RekorResponse;
import dev.sigstore.rekor.client.RekorVerificationException;
import dev.sigstore.rekor.client.RekorVerifier;
import dev.sigstore.tuf.SigstoreTufClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertPath;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A full sigstore keyless signing flow.
 *
 * <p>Note: the implementation is thread-safe assuming the clients (Fulcio, OIDC, Rekor) are
 * thread-safe
 */
public class KeylessSigner implements AutoCloseable {
  /**
   * The instance of the {@link KeylessSigner} will try to reuse a previously acquired certificate
   * if the expiration time on the certificate is more than {@code minSigningCertificateLifetime}
   * time away. Otherwise, it will make a new request (OIDC, Fulcio) to obtain a new updated
   * certificate to use for signing. This is a default value for the remaining lifetime of the
   * signing certificate that is considered good enough.
   */
  public static final Duration DEFAULT_MIN_SIGNING_CERTIFICATE_LIFETIME = Duration.ofMinutes(5);

  private final FulcioClient fulcioClient;
  private final FulcioVerifier fulcioVerifier;
  private final RekorClient rekorClient;
  private final RekorVerifier rekorVerifier;
  private final OidcClients oidcClients;
  private final List<OidcIdentity> oidcIdentities;
  private final Signer signer;
  private final Duration minSigningCertificateLifetime;

  /** The code signing certificate from Fulcio. */
  @GuardedBy("lock")
  private @Nullable CertPath signingCert;

  /**
   * Representation {@link #signingCert} in PEM bytes format. This is used to avoid serializing the
   * certificate for each use.
   */
  @GuardedBy("lock")
  private byte @Nullable [] signingCertPemBytes;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  private KeylessSigner(
      FulcioClient fulcioClient,
      FulcioVerifier fulcioVerifier,
      RekorClient rekorClient,
      RekorVerifier rekorVerifier,
      OidcClients oidcClients,
      List<OidcIdentity> oidcIdentities,
      Signer signer,
      Duration minSigningCertificateLifetime) {
    this.fulcioClient = fulcioClient;
    this.fulcioVerifier = fulcioVerifier;
    this.rekorClient = rekorClient;
    this.rekorVerifier = rekorVerifier;
    this.oidcClients = oidcClients;
    this.oidcIdentities = oidcIdentities;
    this.signer = signer;
    this.minSigningCertificateLifetime = minSigningCertificateLifetime;
  }

  @Override
  public void close() {
    lock.writeLock().lock();
    try {
      signingCert = null;
      signingCertPemBytes = null;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @CheckReturnValue
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private SigstoreTufClient sigstoreTufClient;
    private OidcClients oidcClients;
    private List<OidcIdentity> oidcIdentities = Collections.emptyList();
    private Signer signer;
    private Duration minSigningCertificateLifetime = DEFAULT_MIN_SIGNING_CERTIFICATE_LIFETIME;

    @CanIgnoreReturnValue
    public Builder sigstoreTufClient(SigstoreTufClient sigstoreTufClient) {
      this.sigstoreTufClient = sigstoreTufClient;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder oidcClients(OidcClients oidcClients) {
      this.oidcClients = oidcClients;
      return this;
    }

    /**
     * An allow list OIDC identities to be used during signing. If the OidcClients are misconfigured
     * or pick up unexpected credentials, this should prevent signing from proceeding
     */
    @CanIgnoreReturnValue
    public Builder allowedOidcIdentities(List<OidcIdentity> oidcIdentities) {
      this.oidcIdentities = ImmutableList.copyOf(oidcIdentities);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder signer(Signer signer) {
      this.signer = signer;
      return this;
    }

    /**
     * The instance of the {@link KeylessSigner} will try to reuse a previously acquired certificate
     * if the expiration time on the certificate is more than {@code minSigningCertificateLifetime}
     * time away. Otherwise, it will make a new request (OIDC, Fulcio) to obtain a new updated
     * certificate to use for signing. Default {@code minSigningCertificateLifetime} is {@link
     * #DEFAULT_MIN_SIGNING_CERTIFICATE_LIFETIME}".
     *
     * @param minSigningCertificateLifetime the minimum lifetime of the signing certificate before
     *     renewal
     * @return this builder
     * @see <a href="https://docs.sigstore.dev/fulcio/overview/">Fulcio certificate validity</a>
     */
    @CanIgnoreReturnValue
    public Builder minSigningCertificateLifetime(Duration minSigningCertificateLifetime) {
      this.minSigningCertificateLifetime = minSigningCertificateLifetime;
      return this;
    }

    @CheckReturnValue
    public KeylessSigner build()
        throws CertificateException, IOException, NoSuchAlgorithmException, InvalidKeySpecException,
            InvalidKeyException, InvalidAlgorithmParameterException {
      Preconditions.checkNotNull(sigstoreTufClient, "sigstoreTufClient");
      sigstoreTufClient.update();
      var trustedRoot = sigstoreTufClient.getSigstoreTrustedRoot();
      var fulcioClient =
          FulcioClient.builder().setUri(trustedRoot.getCAs().current().getUri()).build();
      var fulcioVerifier = FulcioVerifier.newFulcioVerifier(trustedRoot);
      var rekorClient =
          RekorClient.builder().setUri(trustedRoot.getTLogs().current().getBaseUrl()).build();
      var rekorVerifier = RekorVerifier.newRekorVerifier(trustedRoot);
      return new KeylessSigner(
          fulcioClient,
          fulcioVerifier,
          rekorClient,
          rekorVerifier,
          oidcClients,
          oidcIdentities,
          signer,
          minSigningCertificateLifetime);
    }

    /**
     * Initialize a builder with the sigstore public good instance tuf root and oidc targets with
     * ecdsa signing.
     */
    @CanIgnoreReturnValue
    public Builder sigstorePublicDefaults() throws IOException, NoSuchAlgorithmException {
      sigstoreTufClient = SigstoreTufClient.builder().usePublicGoodInstance().build();
      oidcClients(OidcClients.DEFAULTS);
      signer(Signers.newEcdsaSigner());
      minSigningCertificateLifetime(DEFAULT_MIN_SIGNING_CERTIFICATE_LIFETIME);
      return this;
    }

    /**
     * Initialize a builder with the sigstore staging instance tuf root and oidc targets with ecdsa
     * signing.
     */
    @CanIgnoreReturnValue
    public Builder sigstoreStagingDefaults() throws IOException, NoSuchAlgorithmException {
      sigstoreTufClient = SigstoreTufClient.builder().useStagingInstance().build();
      oidcClients(OidcClients.STAGING_DEFAULTS);
      signer(Signers.newEcdsaSigner());
      minSigningCertificateLifetime(DEFAULT_MIN_SIGNING_CERTIFICATE_LIFETIME);
      return this;
    }
  }

  /**
   * Sign one or more artifact digests using the keyless signing workflow. The oidc/fulcio dance to
   * obtain a signing certificate will only occur once. The same ephemeral private key will be used
   * to sign all artifacts. This method will renew certificates as they expire.
   *
   * @param artifactDigests sha256 digests of the artifacts to sign.
   * @return a list of keyless singing results.
   */
  @CheckReturnValue
  public List<KeylessSignature> sign(List<byte[]> artifactDigests) throws KeylessSignerException {

    if (artifactDigests.size() == 0) {
      throw new IllegalArgumentException("Require one or more digests");
    }

    var result = ImmutableList.<KeylessSignature>builder();

    for (var artifactDigest : artifactDigests) {
      byte[] signature;
      try {
        signature = signer.signDigest(artifactDigest);
      } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException ex) {
        throw new KeylessSignerException("Failed to sign artifact", ex);
      }

      // Technically speaking, it is unlikely the certificate will expire between signing artifacts
      // However, files might be large, and it might take time to talk to Rekor
      // so we check the certificate expiration here.
      try {
        renewSigningCertificate();
      } catch (FulcioVerificationException
          | UnsupportedAlgorithmException
          | OidcException
          | IOException
          | InterruptedException
          | InvalidKeyException
          | NoSuchAlgorithmException
          | SignatureException
          | CertificateException ex) {
        throw new KeylessSignerException("Failed to obtain signing certificate", ex);
      }

      CertPath signingCert;
      byte[] signingCertPemBytes;
      lock.readLock().lock();
      try {
        signingCert = this.signingCert;
        signingCertPemBytes = this.signingCertPemBytes;
        if (signingCert == null) {
          throw new IllegalStateException("Signing certificate is null");
        }
      } finally {
        lock.readLock().unlock();
      }

      var rekorRequest =
          HashedRekordRequest.newHashedRekordRequest(
              artifactDigest, signingCertPemBytes, signature);

      RekorResponse rekorResponse;
      try {
        rekorResponse = rekorClient.putEntry(rekorRequest);
      } catch (RekorParseException | IOException ex) {
        throw new KeylessSignerException("Failed to put entry in rekor", ex);
      }

      try {
        rekorVerifier.verifyEntry(rekorResponse.getEntry());
      } catch (RekorVerificationException ex) {
        throw new KeylessSignerException("Failed to validate rekor response after signing", ex);
      }

      result.add(
          KeylessSignature.builder()
              .digest(artifactDigest)
              .certPath(signingCert)
              .signature(signature)
              .entry(rekorResponse.getEntry())
              .build());
    }
    return result.build();
  }

  private void renewSigningCertificate()
      throws InterruptedException, CertificateException, IOException, UnsupportedAlgorithmException,
          NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          FulcioVerificationException, OidcException, KeylessSignerException {
    // Check if the certificate is still valid
    lock.readLock().lock();
    try {
      if (signingCert != null) {
        @SuppressWarnings("JavaUtilDate")
        long lifetimeLeft =
            Certificates.getLeaf(signingCert).getNotAfter().getTime() - System.currentTimeMillis();
        if (lifetimeLeft > minSigningCertificateLifetime.toMillis()) {
          // The current certificate is fine, reuse it
          return;
        }
      }
    } finally {
      lock.readLock().unlock();
    }

    // Renew Fulcio certificate
    lock.writeLock().lock();
    try {
      signingCert = null;
      signingCertPemBytes = null;
      OidcToken tokenInfo = oidcClients.getIDToken();

      // check if we have an allow list and if so, ensure the provided token is in there
      if (!oidcIdentities.isEmpty()) {
        var obtainedToken = OidcIdentity.from(tokenInfo);
        if (!oidcIdentities.contains(OidcIdentity.from(tokenInfo))) {
          throw new KeylessSignerException(
              "Obtained Oidc Token "
                  + obtainedToken
                  + " does not match any identities in allow list");
        }
      }

      CertPath renewedSigningCert =
          fulcioClient.signingCertificate(
              CertificateRequest.newCertificateRequest(
                  signer.getPublicKey(),
                  tokenInfo.getIdToken(),
                  signer.sign(
                      tokenInfo.getSubjectAlternativeName().getBytes(StandardCharsets.UTF_8))));
      // TODO: this signing workflow mandates SCTs, but fulcio itself doesn't, figure out a way to
      // allow that to be known

      var trimmed = fulcioVerifier.trimTrustedParent(renewedSigningCert);

      fulcioVerifier.verifySigningCertificate(trimmed);
      this.signingCert = trimmed;
      signingCertPemBytes = Certificates.toPemBytes(signingCert);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Convenience wrapper around {@link #sign(List)} to sign a single digest
   *
   * @param artifactDigest sha256 digest of the artifact to sign.
   * @return a keyless singing results.
   */
  @CheckReturnValue
  public KeylessSignature sign(byte[] artifactDigest) throws KeylessSignerException {
    return sign(List.of(artifactDigest)).get(0);
  }

  /**
   * Convenience wrapper around {@link #sign(List)} to accept files instead of digests
   *
   * @param artifacts list of the artifacts to sign.
   * @return a map of artifacts and their keyless singing results.
   */
  @CheckReturnValue
  public Map<Path, KeylessSignature> signFiles(List<Path> artifacts) throws KeylessSignerException {
    if (artifacts.size() == 0) {
      throw new IllegalArgumentException("Require one or more paths");
    }
    var digests = new ArrayList<byte[]>(artifacts.size());
    for (var artifact : artifacts) {
      var artifactByteSource = com.google.common.io.Files.asByteSource(artifact.toFile());
      try {
        digests.add(artifactByteSource.hash(Hashing.sha256()).asBytes());
      } catch (IOException ex) {
        throw new KeylessSignerException("Failed to hash artifact " + artifact);
      }
    }
    var signingResult = sign(digests);
    var result = ImmutableMap.<Path, KeylessSignature>builder();
    for (int i = 0; i < artifacts.size(); i++) {
      result.put(artifacts.get(i), signingResult.get(i));
    }
    return result.build();
  }

  /**
   * Convenience wrapper around {@link #sign(List)} to accept a file instead of digests
   *
   * @param artifact the artifacts to sign.
   * @return a keyless singing results.
   */
  @CheckReturnValue
  public KeylessSignature signFile(Path artifact) throws KeylessSignerException {
    return signFiles(List.of(artifact)).get(artifact);
  }
}
