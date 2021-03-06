package com.quorum.tessera.transaction;

import com.quorum.tessera.api.model.*;
import com.quorum.tessera.enclave.Enclave;
import com.quorum.tessera.enclave.EncodedPayload;
import com.quorum.tessera.enclave.PayloadEncoder;
import com.quorum.tessera.enclave.RawTransaction;
import com.quorum.tessera.enclave.model.MessageHash;
import com.quorum.tessera.enclave.model.MessageHashFactory;
import com.quorum.tessera.encryption.PublicKey;
import com.quorum.tessera.nacl.NaclException;
import com.quorum.tessera.service.Service;
import com.quorum.tessera.transaction.exception.EnclaveNotAvailableException;
import com.quorum.tessera.transaction.exception.KeyNotFoundException;
import com.quorum.tessera.transaction.exception.PublishPayloadException;
import com.quorum.tessera.transaction.exception.TransactionNotFoundException;
import com.quorum.tessera.transaction.model.EncryptedRawTransaction;
import com.quorum.tessera.transaction.model.EncryptedTransaction;
import com.quorum.tessera.util.Base64Decoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Delegate/Mediator object to normalise calls/interactions between Enclave and
 * Base64Decoder
 *
 * @see {Base64Decoder}
 * @see {Enclave}
 */
public class TransactionManagerImpl implements TransactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionManagerImpl.class);

    private final PayloadEncoder payloadEncoder;

    private final Base64Decoder base64Decoder;

    private final EncryptedTransactionDAO encryptedTransactionDAO;

    private final EncryptedRawTransactionDAO encryptedRawTransactionDAO;

    private final PayloadPublisher payloadPublisher;

    private final Enclave enclave;

    private final ResendManager resendManager;

    private final MessageHashFactory messageHashFactory = MessageHashFactory.create();

    public TransactionManagerImpl(
            Base64Decoder base64Decoder,
            PayloadEncoder payloadEncoder,
            EncryptedTransactionDAO encryptedTransactionDAO,
            PayloadPublisher payloadPublisher,
            Enclave enclave,
            EncryptedRawTransactionDAO encryptedRawTransactionDAO,
            ResendManager resendManager) {

        this.base64Decoder = Objects.requireNonNull(base64Decoder);
        this.payloadEncoder = Objects.requireNonNull(payloadEncoder);
        this.encryptedTransactionDAO = Objects.requireNonNull(encryptedTransactionDAO);
        this.payloadPublisher = Objects.requireNonNull(payloadPublisher);
        this.enclave = Objects.requireNonNull(enclave);
        this.encryptedRawTransactionDAO = Objects.requireNonNull(encryptedRawTransactionDAO);
        this.resendManager = Objects.requireNonNull(resendManager);
    }

    @Override
    @Transactional
    public SendResponse send(SendRequest sendRequest) {
        
        validateEnclaveStatus();
        
        final String sender = sendRequest.getFrom();

        final PublicKey senderPublicKey = Optional.ofNullable(sender)
                .map(base64Decoder::decode)
                .map(PublicKey::from)
                .orElseGet(enclave::defaultPublicKey);

        final byte[][] recipients = Stream.of(sendRequest)
                .filter(sr -> Objects.nonNull(sr.getTo()))
                .flatMap(s -> Stream.of(s.getTo()))
                .map(base64Decoder::decode)
                .toArray(byte[][]::new);

        final List<PublicKey> recipientList = Stream
                .of(recipients)
                .map(PublicKey::from)
                .collect(Collectors.toList());

        recipientList.add(senderPublicKey);

        recipientList.addAll(enclave.getForwardingKeys());

        final byte[] raw = sendRequest.getPayload();

        final EncodedPayload payload = enclave.encryptPayload(raw, senderPublicKey, recipientList);

        final MessageHash transactionHash = Optional.of(payload)
                .map(EncodedPayload::getCipherText)
                .map(messageHashFactory::createFromCipherText).get();

        final EncryptedTransaction newTransaction
            = new EncryptedTransaction(transactionHash, this.payloadEncoder.encode(payload));

        this.encryptedTransactionDAO.save(newTransaction);

        recipientList.forEach(recipient -> {
            final EncodedPayload outgoing = payloadEncoder.forRecipient(payload, recipient);
            payloadPublisher.publishPayload(outgoing, recipient);
        });

        final byte[] key = transactionHash.getHashBytes();

        final String encodedKey = base64Decoder.encodeToString(key);

        return new SendResponse(encodedKey);
    }

    @Override
    @Transactional
    public SendResponse sendSignedTransaction(SendSignedRequest sendRequest) {
        
        validateEnclaveStatus();
        
        final byte[][] recipients = Stream.of(sendRequest)
            .filter(sr -> Objects.nonNull(sr.getTo()))
            .flatMap(s -> Stream.of(s.getTo()))
            .map(base64Decoder::decode)
            .toArray(byte[][]::new);

        final List<PublicKey> recipientList = Stream
            .of(recipients)
            .map(PublicKey::from)
            .collect(Collectors.toList());

        recipientList.addAll(enclave.getForwardingKeys());

        MessageHash messageHash = new MessageHash(sendRequest.getHash());

        EncryptedRawTransaction encryptedRawTransaction = encryptedRawTransactionDAO.retrieveByHash(messageHash)
            .orElseThrow(() -> new TransactionNotFoundException("Raw Transaction with hash " + messageHash + " was not found"));

        final EncodedPayload payload
            = enclave.encryptPayload(encryptedRawTransaction.toRawTransaction(), recipientList);


        final EncryptedTransaction newTransaction
            = new EncryptedTransaction(messageHash, this.payloadEncoder.encode(payload));

        this.encryptedTransactionDAO.save(newTransaction);

        recipientList.forEach(recipient -> {
            final EncodedPayload toPublish = payloadEncoder.forRecipient(payload, recipient);
            payloadPublisher.publishPayload(toPublish, recipient);
        });

        final byte[] key = messageHash.getHashBytes();

        final String encodedKey = base64Decoder.encodeToString(key);

        return new SendResponse(encodedKey);
    }

    @Override
    @Transactional
    public ResendResponse resend(ResendRequest request) {

        validateEnclaveStatus();
        
        final byte[] publicKeyData = base64Decoder.decode(request.getPublicKey());
        PublicKey recipientPublicKey = PublicKey.from(publicKeyData);
        if (request.getType() == ResendRequestType.ALL) {

            encryptedTransactionDAO
                .retrieveAllTransactions()
                .stream()
                .map(EncryptedTransaction::getEncodedPayload)
                .map(payloadEncoder::decode)
                .filter(payload -> {
                    final boolean isRecipient = payload.getRecipientKeys().contains(recipientPublicKey);
                    final boolean isSender = Objects.equals(payload.getSenderKey(), recipientPublicKey);
                    return isRecipient || isSender;
                }).forEach(payload -> {
                    if (Objects.equals(payload.getSenderKey(), recipientPublicKey)) {
                        final PublicKey decryptedKey = searchForRecipientKey(payload).orElseThrow(
                            () -> {
                                final MessageHash hash = MessageHashFactory.create()
                                    .createFromCipherText(payload.getCipherText());
                                return new KeyNotFoundException("No key found as recipient of message " + hash);
                            }
                        );
                        payload.getRecipientKeys().add(decryptedKey);
                    }

                    try {
                        payloadPublisher.publishPayload(payload, recipientPublicKey);
                    } catch (PublishPayloadException ex) {
                        LOGGER.warn("Unable to publish payload to recipient {} during resend", recipientPublicKey.encodeToBase64());
                    }

                });

            return new ResendResponse();
        } else {

            final byte[] hashKey = base64Decoder.decode(request.getKey());
            final MessageHash messageHash = new MessageHash(hashKey);

            final EncryptedTransaction encryptedTransaction = encryptedTransactionDAO
                .retrieveByHash(messageHash)
                .orElseThrow(() -> new TransactionNotFoundException("Message with hash " + messageHash + " was not found"));

            final EncodedPayload payload = payloadEncoder.decode(encryptedTransaction.getEncodedPayload());

            final EncodedPayload returnValue;
            if (Objects.equals(payload.getSenderKey(), recipientPublicKey)) {
                final PublicKey decryptedKey = searchForRecipientKey(payload).orElseThrow(RuntimeException::new);
                payload.getRecipientKeys().add(decryptedKey);
                returnValue = payload;
            } else {
                //this is our tx
                returnValue = payloadEncoder.forRecipient(payload, recipientPublicKey);
            }

            return new ResendResponse(payloadEncoder.encode(returnValue));
        }
    }

    @Override
    public MessageHash storePayload(byte[] input) {

        validateEnclaveStatus();
        
        final EncodedPayload payload = payloadEncoder.decode(input);

        final MessageHash transactionHash = Optional.of(payload)
            .map(EncodedPayload::getCipherText)
            .map(messageHashFactory::createFromCipherText).get();

        if (enclave.getPublicKeys().contains(payload.getSenderKey())) {

            this.resendManager.acceptOwnMessage(input);

        } else {

            //this is a tx from someone else
            this.encryptedTransactionDAO.save(new EncryptedTransaction(transactionHash, input));
            LOGGER.info("Stored payload with hash {}", transactionHash);

        }

        return transactionHash;
    }

    @Override
    @Transactional
    public void delete(DeleteRequest request) {
        final byte[] hashBytes = base64Decoder.decode(request.getKey());
        final MessageHash messageHash = new MessageHash(hashBytes);

        LOGGER.info("Received request to delete message with hash {}", messageHash);
        this.encryptedTransactionDAO.delete(messageHash);

    }

    @Override
    @Transactional
    public ReceiveResponse receive(ReceiveRequest request) {
        
        validateEnclaveStatus();
        
        final byte[] key = base64Decoder.decode(request.getKey());

        final Optional<byte[]> to = Optional
                .ofNullable(request.getTo())
                .filter(str -> !str.isEmpty())
                .map(base64Decoder::decode);

        final MessageHash hash = new MessageHash(key);
        LOGGER.info("Lookup transaction {}",hash);
        
        final EncryptedTransaction encryptedTransaction = encryptedTransactionDAO
                .retrieveByHash(hash)
                .orElseThrow(() -> new TransactionNotFoundException("Message with hash " + hash + " was not found"));

        final EncodedPayload payload = Optional.of(encryptedTransaction)
                .map(EncryptedTransaction::getEncodedPayload)
                .map(payloadEncoder::decode)
                .orElseThrow(() -> new IllegalStateException("Unable to decode previously encoded payload"));

        PublicKey recipientKey = to.map(PublicKey::from)
            .orElse(searchForRecipientKey(payload)
                .orElseThrow(() -> new NoRecipientKeyFoundException("No suitable recipient keys found to decrypt payload for : " + hash))
            );

        byte[] response = enclave.unencryptTransaction(payload, recipientKey);

        return new ReceiveResponse(response);

    }

    private Optional<PublicKey> searchForRecipientKey(final EncodedPayload payload) {
        for (final PublicKey potentialMatchingKey : enclave.getPublicKeys()) {
            try {
                enclave.unencryptTransaction(payload, potentialMatchingKey);
                return Optional.of(potentialMatchingKey);
            } catch (IndexOutOfBoundsException | NaclException ex) {
                LOGGER.debug("Attempted payload decryption using wrong key, discarding.");
            }
        }
        return Optional.empty();
    }

    @Override
    public StoreRawResponse store(StoreRawRequest storeRequest) {
        
        validateEnclaveStatus();
        
        RawTransaction rawTransaction = enclave.encryptRawPayload(storeRequest.getPayload(),
            storeRequest.getFrom().map(PublicKey::from).orElseGet(enclave::defaultPublicKey));
        MessageHash hash = messageHashFactory.createFromCipherText(rawTransaction.getEncryptedPayload());

        EncryptedRawTransaction encryptedRawTransaction = new EncryptedRawTransaction(hash,
            rawTransaction.getEncryptedPayload(),
            rawTransaction.getEncryptedKey(),
            rawTransaction.getNonce().getNonceBytes(),
            rawTransaction.getFrom().getKeyBytes());

        encryptedRawTransactionDAO.save(encryptedRawTransaction);

        return new StoreRawResponse(encryptedRawTransaction.getHash().getHashBytes());
    }
    
    /*
    FIXME: This is a workaround for an issue with exception handing when proxying beans. 
    This needs to be done using an invocationhandler or similar proxy that provides around advice.
    */
    private void validateEnclaveStatus() {
        if(enclave.status() == Service.Status.STOPPED) {
            throw new EnclaveNotAvailableException();
        }
    }
    
}
