package com.streamr.client;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import com.streamr.client.exceptions.AmbiguousResultsException;
import com.streamr.client.exceptions.ResourceNotFoundException;
import com.streamr.client.rest.Stream;
import com.streamr.client.utils.HttpUtils;
import okhttp3.*;

import java.io.IOException;
import java.net.URL;
import java.util.List;

/**
 * This class exposes the main set of methods available for application developers.
 * It inherits the functionality in StreamrWebsocketClient and adds methods for interacting
 * with the RESTful API endpoints.
 */
public class StreamrClient extends StreamrWebsocketClient {

    public static final JsonAdapter<Stream> streamJsonAdapter = MOSHI.adapter(Stream.class);
    public static final JsonAdapter<List<Stream>> streamListJsonAdapter = MOSHI.adapter(Types.newParameterizedType(List.class, Stream.class));

    /**
     * Creates a StreamrClient with default options
     */
    public StreamrClient() {
        super(new StreamrClientOptions());
    }

    public StreamrClient(String apiKey) {
        this(new StreamrClientOptions(apiKey));
    }

    public StreamrClient(StreamrClientOptions options) {
        super(options);
    }

    /*
     * Helper functions
     */

    private Request.Builder addAuthenticationHeader(Request.Builder builder) {
        if (!session.isAuthenticated()) {
            return builder;
        } else {
            return builder.addHeader("Authorization", "Bearer " + session.getSessionToken());
        }
    }

    private <T> T execute(Request request, JsonAdapter<T> adapter) throws IOException {
        OkHttpClient client = new OkHttpClient();

        // Execute the request and retrieve the response.
        Response response = client.newCall(request).execute();
        HttpUtils.assertSuccessful(response);

        // Deserialize HTTP response to concrete type.
        return adapter.fromJson(response.body().source());
    }

    private <T> T get(HttpUrl url, JsonAdapter<T> adapter) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        Request request = addAuthenticationHeader(builder).build();
        return execute(request, adapter);
    }

    private <T> T post(HttpUrl url, String requestBody, JsonAdapter<T> adapter) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(HttpUtils.jsonType, requestBody));
        Request request = addAuthenticationHeader(builder).build();
        return execute(request, adapter);
    }

    /*
     * Stream endpoints
     */

    public Stream getStream(String streamId) throws IOException, ResourceNotFoundException {
        if (streamId == null) {
            throw new IllegalArgumentException("streamId cannot be null!");
        }

        HttpUrl url = HttpUrl.parse(options.getRestApiUrl() + "/streams/" + streamId);
        return get(url, streamJsonAdapter);
    }

    public Stream getStreamByName(String name) throws IOException, AmbiguousResultsException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Stream name must be specified!");
        }

        HttpUrl url = HttpUrl.parse(options.getRestApiUrl() + "/streams")
                .newBuilder()
                .setQueryParameter("name", name)
                .build();

        List<Stream> matches = get(url, streamListJsonAdapter);
        if (matches.size() == 1) {
            return matches.get(0);
        } else if (matches.isEmpty()) {
            throw new ResourceNotFoundException("stream by name: " + name);
        } else {
            throw new AmbiguousResultsException("Name is not unique! Multiple streams found by name: " + name);
        }
    }

    public Stream createStream(Stream stream) throws IOException {
        if (stream.getName() == null || stream.getName().isEmpty()) {
            throw new IllegalArgumentException("The stream name must be set!");
        }

        HttpUrl url = HttpUrl.parse(options.getRestApiUrl() + "/streams");
        return post(url, streamJsonAdapter.toJson(stream), streamJsonAdapter);
    }

}
