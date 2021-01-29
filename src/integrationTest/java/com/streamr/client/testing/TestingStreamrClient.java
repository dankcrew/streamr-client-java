package com.streamr.client.testing;

import com.streamr.client.MessageHandler;
import com.streamr.client.StreamrClient;
import com.streamr.client.options.EncryptionOptions;
import com.streamr.client.options.ResendOption;
import com.streamr.client.options.SigningOptions;
import com.streamr.client.options.StreamrClientOptions;
import com.streamr.client.protocol.control_layer.ControlMessage;
import com.streamr.client.protocol.message_layer.StreamMessage;
import com.streamr.client.rest.EthereumAuthenticationMethod;
import com.streamr.client.rest.ResourceNotFoundException;
import com.streamr.client.rest.Stream;
import com.streamr.client.rest.StreamrRestClient;
import com.streamr.client.rest.UserInfo;
import com.streamr.client.subs.Subscription;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TestingStreamrClient extends StreamrClient {
  public static StreamrClient createUnauthenticatedClient() {
    return new StreamrClient(
        new StreamrClientOptions(
            null,
            SigningOptions.getDefault(),
            EncryptionOptions.getDefault(),
            TestingMeta.WEBSOCKET_URL),
        new StreamrRestClient(TestingMeta.REST_URL, null));
  }

  public static StreamrClient createClientWithPrivateKey(final String privateKey) {
    return new StreamrClient(
        createOptionsWithPrivateKey(privateKey),
        new StreamrRestClient(TestingMeta.REST_URL, privateKey));
  }

  private static StreamrClientOptions createOptionsWithPrivateKey(final String privateKey) {
    return new StreamrClientOptions(
        new EthereumAuthenticationMethod(privateKey),
        SigningOptions.getDefault(),
        EncryptionOptions.getDefault(),
        TestingMeta.WEBSOCKET_URL);
  }

  List<StreamMessage> receivedStreamMessages = new ArrayList<>();

  public TestingStreamrClient(final StreamrClientOptions options, final String privateKey) {
    super(
        options,
        new StreamrRestClient(TestingMeta.REST_URL, privateKey) {
          @Override
          public String getSessionToken() {
            return "sessionToken";
          }
        });
  }

  public TestingStreamrClient(
      final StreamrClientOptions options, final StreamrRestClient restClient) {
    super(options, restClient);
  }

  public void receiveMessage(ControlMessage msg) {
    handleMessage(msg.toJson());
  }

  public List<StreamMessage> getReceivedStreamMessages() {
    return receivedStreamMessages;
  }

  @Override
  public Subscription subscribe(
      Stream stream,
      int partition,
      MessageHandler handler,
      ResendOption resendOption,
      boolean isExplicitResend) {
    // Capture received StreamMessages
    MessageHandler loggingHandler =
        (sub, message) -> {
          receivedStreamMessages.add(message);
          handler.onMessage(sub, message);
        };
    return super.subscribe(stream, partition, loggingHandler, resendOption, isExplicitResend);
  }

  @Override
  public UserInfo getUserInfo() {
    return new UserInfo("name", "username");
  }

  @Override
  public String getSessionToken() {
    return "sessionToken";
  }

  @Override
  public Stream getStream(String streamId) throws IOException, ResourceNotFoundException {
    // Return a default mock
    Stream stream =
        new Stream.Builder()
            .withName("default mock stream from TestingStreamrClient")
            .withDescription("")
            .withId(streamId)
            .withRequireSignedData(false)
            .withRequireEncryptedData(false)
            .createStream();
    return stream;
  }
}
