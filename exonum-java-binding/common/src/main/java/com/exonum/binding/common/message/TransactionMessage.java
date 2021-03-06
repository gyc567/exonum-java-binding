/*
 * Copyright 2018 The Exonum Team
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
 *
 */

package com.exonum.binding.common.message;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.exonum.binding.common.crypto.CryptoFunction;
import com.exonum.binding.common.crypto.CryptoFunctions;
import com.exonum.binding.common.crypto.CryptoFunctions.Ed25519;
import com.exonum.binding.common.crypto.KeyPair;
import com.exonum.binding.common.crypto.PublicKey;
import com.exonum.binding.common.hash.HashCode;
import com.exonum.messages.core.Messages;
import com.exonum.messages.core.Messages.CoreMessage;
import com.exonum.messages.core.runtime.Base.AnyTx;
import com.exonum.messages.core.runtime.Base.CallInfo;
import com.exonum.messages.crypto.Types;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import java.nio.ByteBuffer;

/**
 * An Exonum transaction message.
 */
public interface TransactionMessage {

  /**
   * Returns a public key of the author of the transaction message.
   */
  PublicKey getAuthor();

  /**
   * Returns the numeric identifier of the service instance this message belongs to.
   */
  int getServiceId();

  /**
   * Returns the transaction type identifier which is unique within the service.
   */
  int getTransactionId();

  /**
   * Returns the payload containing the serialized transaction parameters.
   */
  // We use ByteString instead of byte[] as most often the payload contains a protobuf message,
  // which can be parsed from a ByteString directly, with no intermediate copying to byte[].
  ByteString getPayload();

  /**
   * Returns the SHA-256 hash of this message.
   *
   * <p>Please note that the hash is <em>not</em> necessarily calculated over the
   * {@linkplain #toBytes() whole message binary representation}; the algorithm is defined
   * in the signed message.
   */
  HashCode hash();

  /**
   * Returns the <a href="https://ed25519.cr.yp.to/">Ed25519</a> signature
   * over this binary message.
   *
   * <p>The signature is <strong>not</strong> guaranteed to be valid and must be verified against
   * the {@linkplain #getAuthor() signer’s public key}.
   *
   * @see CryptoFunctions#ed25519()
   */
  byte[] getSignature();

  /**
   * Returns the transaction message in binary format.
   */
  byte[] toBytes();

  /**
   * Creates a new builder for the transaction message.
   */
  static Builder builder() {
    return new Builder();
  }

  /**
   * Creates the transaction message from the given bytes array.
   */
  static TransactionMessage fromBytes(byte[] bytes) {
    try {
      Messages.SignedMessage signedMessage = Messages.SignedMessage.parseFrom(bytes);
      return new ParsedTransactionMessage(signedMessage);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Builder for the binary transaction message.
   */
  class Builder {
    private static final CryptoFunction DEFAULT_CRYPTO_FUNCTION = CryptoFunctions.ed25519();
    private Integer serviceId;
    private Integer transactionId;
    private ByteString payload;
    private KeyPair keys;
    private CryptoFunction cryptoFunction;

    /**
     * Sets service identifier to the transaction message.
     */
    public Builder serviceId(int serviceId) {
      this.serviceId = serviceId;
      return this;
    }

    /**
     * Sets transaction identifier to the transaction message.
     */
    public Builder transactionId(int transactionId) {
      this.transactionId = transactionId;
      return this;
    }

    /**
     * Sets a payload of the transaction message.
     */
    public Builder payload(byte[] payload) {
      return payload(ByteString.copyFrom(payload));
    }

    /**
     * Sets a payload of the transaction message.
     */
    public Builder payload(ByteBuffer payload) {
      return payload(ByteString.copyFrom(payload));
    }

    /**
     * Sets a payload of the transaction message.
     */
    public Builder payload(MessageLite payload) {
      return payload(payload.toByteString());
    }

    /**
     * Sets a payload of the transaction message.
     */
    public Builder payload(ByteString payload) {
      this.payload = payload;
      return this;
    }

    /**
     * Sets the Ed25519 key pair to use to sign the message.
     *
     * @param keys a key pair with a private and public keys. The public key is included
     *     in the message as an author key of the message. The private key is used for signing
     *     the message, but not included in it
     */
    public Builder signedWith(KeyPair keys) {
      return signedWith(keys, DEFAULT_CRYPTO_FUNCTION);
    }

    /**
     * Sets the key pair and the crypto function to use to sign the message.
     *
     * @param keys a key pair with a private and public keys. The public key is included
     *     in the message as an author key of the message. The private key is used for signing
     *     the message, but not included in it
     * @param crypto a cryptographic function to use
     */
    public Builder signedWith(KeyPair keys, CryptoFunction crypto) {
      this.keys = checkNotNull(keys);
      this.cryptoFunction = checkNotNull(crypto);
      return this;
    }

    /**
     * Signs the message with the given Ed25519 keys, creating a new signed binary
     * transaction message. A shorthand for {@code signedWith(keys).build()}.
     *
     * @param keys a key pair to {@linkplain #signedWith(KeyPair) sign} the message
     * @return a new signed binary transaction message
     * @throws IllegalStateException if any field weren't set
     * @throws IllegalArgumentException if the public key has wrong size
     */
    public TransactionMessage sign(KeyPair keys) {
      return signedWith(keys).build();
    }

    /**
     * Signs the message, creating a new signed binary transaction message.
     *
     * @return a new signed binary transaction message
     * @throws IllegalStateException if any field weren't set
     * @throws IllegalArgumentException if the public key has wrong size
     */
    public TransactionMessage build() {
      checkRequiredFieldsSet();
      PublicKey authorPublicKey = keys.getPublicKey();
      checkArgument(authorPublicKey.size() == Ed25519.PUBLIC_KEY_BYTES,
          "PublicKey has invalid size (%s), expected: %s. Key: %s",
          authorPublicKey.size(), Ed25519.PUBLIC_KEY_BYTES, authorPublicKey);

      byte[] exonumMessage = CoreMessage.newBuilder()
          .setAnyTx(AnyTx.newBuilder()
              .setCallInfo(CallInfo.newBuilder()
                  .setInstanceId(serviceId)
                  .setMethodId(transactionId)
                  .build())
              .setArguments(payload)
              .build())
          .build()
          .toByteArray();

      byte[] signature = cryptoFunction.signMessage(exonumMessage, keys.getPrivateKey());

      Messages.SignedMessage signedMessage = Messages.SignedMessage.newBuilder()
          .setAuthor(Types.PublicKey.newBuilder()
              .setData(ByteString.copyFrom(authorPublicKey.toBytes()))
              .build())
          .setPayload(ByteString.copyFrom(exonumMessage))
          .setSignature(Types.Signature.newBuilder()
              .setData(ByteString.copyFrom(signature))
              .build())
          .build();

      // todo [ECR-3575]: What isn't quite good in using ParsedTransactionMessage here is that
      //    we create a transaction to send it, but still unnecessarily parse it in Parsed...
      //    constructor to implement most get operations (getPayload, etc.)!
      //    Shall we do that lazily? Have a separate implementation
      //    (e.g., abstract BaseTransactionMessage depending on Consensus.SignedMessage +
      //    ParsedTransactionMessage and a transaction message that gets all the initial parts,
      //    or a transaction message that does parsing lazily)?
      //    -
      //    Alternatively, we may have two separate *types*: one for a freshly-signed transaction
      //    ready to be serialized and submitted to the blockchain; and another one for
      //    a parsed/signed-by-someone-else transaction read from the blockchain.
      return new ParsedTransactionMessage(signedMessage);
    }

    private void checkRequiredFieldsSet() {
      String undefinedFields = "";
      undefinedFields = serviceId == null ? undefinedFields + " serviceId" : undefinedFields;
      undefinedFields =
          transactionId == null ? undefinedFields + " transactionId" : undefinedFields;
      undefinedFields = payload == null ? undefinedFields + " payload" : undefinedFields;
      undefinedFields = keys == null ? undefinedFields + " keys" : undefinedFields;
      if (!undefinedFields.isEmpty()) {
        throw new IllegalStateException(
            "Following field(s) are required but weren't set: " + undefinedFields);
      }
    }

    private Builder() {
    }
  }

}
