package com.streamr.client.utils

import com.streamr.client.exceptions.InvalidGroupKeyException
import com.streamr.client.exceptions.InvalidGroupKeyRequestException
import com.streamr.client.exceptions.InvalidGroupKeyResponseException
import com.streamr.client.protocol.message_layer.MessageID
import com.streamr.client.protocol.message_layer.StreamMessage
import com.streamr.client.protocol.message_layer.StreamMessageV31
import org.apache.commons.codec.binary.Hex
import spock.lang.Specification

import java.security.SecureRandom
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function

class KeyExchangeUtilSpec extends Specification {
    SecureRandom secureRandom = new SecureRandom()
    UnencryptedGroupKey genKey(int keyLength) {
        return genKey(keyLength, new Date())
    }

    UnencryptedGroupKey genKey(int keyLength, Date start) {
        byte[] keyBytes = new byte[keyLength]
        secureRandom.nextBytes(keyBytes)
        return new UnencryptedGroupKey(Hex.encodeHexString(keyBytes), start)
    }
    KeyExchangeUtil util
    KeyStorage storage
    MessageCreationUtil messageCreationUtil
    Consumer<StreamMessage> publish
    KeyExchangeUtil.SetGroupKeysFunction setGroupKeysFunction
    StreamMessage published
    StreamMessage response = new StreamMessageV31(new MessageID("subscriberId", 0, 5145, 0, "publisherId", ""), null,
            StreamMessage.ContentType.GROUP_KEY_RESPONSE_SIMPLE, StreamMessage.EncryptionType.RSA, "response", StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
    EncryptionUtil encryptionUtil = new EncryptionUtil()
    AddressValidityUtil addressValidityUtil = new AddressValidityUtil({ String id -> new ArrayList<>()}, { String s1, String s2 -> s1 == "streamId" && s2 == "subscriberId"},
            { String id -> new ArrayList<>()}, { String s, String p -> true})
    UnencryptedGroupKey received
    void setup() {
        storage = Mock(KeyStorage)
        messageCreationUtil = Mock(MessageCreationUtil)
        publish = new Consumer<StreamMessage>() {
            @Override
            void accept(StreamMessage streamMessage) {
                published = streamMessage
            }
        }
        setGroupKeysFunction = new KeyExchangeUtil.SetGroupKeysFunction() {
            @Override
            void apply(String streamId, String publisherId, ArrayList<UnencryptedGroupKey> keys) {
                assert streamId == "streamId"
                assert publisherId == "publisherId"
                assert keys.size() == 1
                received = keys[0]
            }
        }
        util = new KeyExchangeUtil(storage, messageCreationUtil, encryptionUtil, addressValidityUtil, publish, setGroupKeysFunction)
    }
    void "should reject unsigned request"() {
        MessageID id = new MessageID("publisherInbox", 0, 414, 0, "subscriberId", "msgChainId")
        Map<String, Object> content = ["publicKey": "rsa public key", "streamId": "streamId"]
        StreamMessage request = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_REQUEST,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_NONE, null)
        when:
        util.handleGroupKeyRequest(request)
        then:
        InvalidGroupKeyRequestException e = thrown(InvalidGroupKeyRequestException)
        e.message == "Received unsigned group key request (the public key must be signed to avoid MitM attacks)."
    }
    void "should reject request from invalid subscriber"() {
        MessageID id = new MessageID("publisherInbox", 0, 414, 0, "wrong-subscriberId", "msgChainId")
        Map<String, Object> content = ["publicKey": "rsa public key", "streamId": "streamId"]
        StreamMessage request = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_REQUEST,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
        when:
        util.handleGroupKeyRequest(request)
        then:
        InvalidGroupKeyRequestException e = thrown(InvalidGroupKeyRequestException)
        e.message == "Received group key request for stream 'streamId' from invalid address 'wrong-subscriberId'"
    }
    void "should reject request for a stream for which the client does not have a group key"() {
        MessageID id = new MessageID("publisherInbox", 0, 414, 0, "subscriberId", "msgChainId")
        Map<String, Object> content = ["publicKey": "rsa public key", "streamId": "streamId"]
        StreamMessage request = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_REQUEST,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
        when:
        util.handleGroupKeyRequest(request)
        then:
        1 * storage.getLatestKey("streamId") >> null
        InvalidGroupKeyRequestException e = thrown(InvalidGroupKeyRequestException)
        e.message == "Received group key request for stream 'streamId' but no group key is set"
    }
    void "should send group key response (latest key)"() {
        MessageID id = new MessageID("publisherInbox", 0, 414, 0, "subscriberId", "msgChainId")
        Map<String, Object> content = ["publicKey": encryptionUtil.publicKeyAsPemString, "streamId": "streamId"]
        StreamMessage request = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_REQUEST,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
        UnencryptedGroupKey key = genKey(32, new Date(123))
        when:
        util.handleGroupKeyRequest(request)
        then:
        1 * storage.getLatestKey("streamId") >> key
        1 * messageCreationUtil.createGroupKeyResponse(*_) >> { arguments ->
            assert arguments[0] == "subscriberId"
            assert arguments[1] == "streamId"
            ArrayList<EncryptedGroupKey> keys = arguments[2]
            assert keys.size() == 1
            EncryptedGroupKey received = keys[0]
            assert Hex.encodeHexString(encryptionUtil.decryptWithPrivateKey(received.groupKeyHex)) == key.groupKeyHex
            assert received.startTime == key.startTime
            return response
        }
        published == response
    }
    void "should send group key response (range of keys)"() {
        MessageID id = new MessageID("publisherInbox", 0, 414, 0, "subscriberId", "msgChainId")
        // Need to use Double because Moshi parser converts all JSON numbers to double
        Map<String, Object> content = ["publicKey": encryptionUtil.publicKeyAsPemString, "streamId": "streamId", "range": ["start": new Double(123), "end": new Double(456)]]
        StreamMessage request = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_REQUEST,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
        UnencryptedGroupKey key1 = genKey(32, new Date(123))
        UnencryptedGroupKey key2 = genKey(32, new Date(300))
        when:
        util.handleGroupKeyRequest(request)
        then:
        1 * storage.getKeysBetween("streamId", 123L, 456L) >> [key1, key2]
        1 * messageCreationUtil.createGroupKeyResponse(*_) >> { arguments ->
            assert arguments[0] == "subscriberId"
            assert arguments[1] == "streamId"
            ArrayList<EncryptedGroupKey> keys = arguments[2]
            assert keys.size() ==2
            EncryptedGroupKey received1 = keys[0]
            assert Hex.encodeHexString(encryptionUtil.decryptWithPrivateKey(received1.groupKeyHex)) == key1.groupKeyHex
            assert received1.startTime == key1.startTime
            EncryptedGroupKey received2 = keys[1]
            assert Hex.encodeHexString(encryptionUtil.decryptWithPrivateKey(received2.groupKeyHex)) == key2.groupKeyHex
            assert received2.startTime == key2.startTime
            return response
        }
        published == response
    }
    void "should reject unsigned response"() {
        MessageID id = new MessageID("subscriberInbox", 0, 414, 0, "publisherId", "msgChainId")
        Map<String, Object> content = ["keys": [], "streamId": "streamId"]
        StreamMessage response = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_RESPONSE_SIMPLE,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_NONE, null)
        when:
        util.handleGroupKeyResponse(response)
        then:
        InvalidGroupKeyResponseException e = thrown(InvalidGroupKeyResponseException)
        e.message == "Received unsigned group key response (it must be signed to avoid MitM attacks)."
    }
    void "should reject response with invalid group key"() {
        SecureRandom secureRandom = new SecureRandom()
        byte[] keyBytes = new byte[16]
        secureRandom.nextBytes(keyBytes)
        String groupKeyHex = Hex.encodeHexString(keyBytes)
        String encryptedGroupKeyHex = EncryptionUtil.encryptWithPublicKey(groupKeyHex, encryptionUtil.getPublicKeyAsPemString())

        MessageID id = new MessageID("subscriberInbox", 0, 414, 0, "publisherId", "msgChainId")
        // Need to use Double because the Moshi parser converts all JSON numbers to double
        Map<String, Object> content = ["keys": [["groupKey": encryptedGroupKeyHex, "start": new Double(123)]], "streamId": "streamId"]
        StreamMessage response = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_RESPONSE_SIMPLE,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
        when:
        util.handleGroupKeyResponse(response)
        then:
        InvalidGroupKeyResponseException e = thrown(InvalidGroupKeyResponseException)
        e.message == "Group key must be 256 bits long, but got a key length of 128 bits."
    }
    void "should update client options and subscriptions with received group key"() {
        SecureRandom secureRandom = new SecureRandom()
        byte[] keyBytes = new byte[32]
        secureRandom.nextBytes(keyBytes)
        String groupKeyHex = Hex.encodeHexString(keyBytes)
        String encryptedGroupKeyHex = EncryptionUtil.encryptWithPublicKey(groupKeyHex, encryptionUtil.getPublicKeyAsPemString())

        MessageID id = new MessageID("subscriberInbox", 0, 414, 0, "publisherId", "msgChainId")
        // Need to use Double because the Moshi parser converts all JSON numbers to double
        Map<String, Object> content = ["keys": [["groupKey": encryptedGroupKeyHex, "start": new Double(123)]], "streamId": "streamId"]
        StreamMessage response = new StreamMessageV31(id, null, StreamMessage.ContentType.GROUP_KEY_RESPONSE_SIMPLE,
                StreamMessage.EncryptionType.NONE, content, StreamMessage.SignatureType.SIGNATURE_TYPE_ETH, "signature")
        when:
        util.handleGroupKeyResponse(response)
        then:
        received.groupKeyHex == groupKeyHex
        received.startTime == 123L
    }
}