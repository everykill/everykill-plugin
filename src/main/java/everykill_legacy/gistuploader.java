package everykill_legacy;

import com.google.gson.JsonObject;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Pushes a snapshot to an existing GitHub gist so it can be fetched from
 * elsewhere. Entirely optional; the plugin writes to disk regardless.
 *
 * The token is entered by the user in RuneLite's config and stored by RuneLite.
 * It is only ever sent to api.github.com over HTTPS.
 */
@Slf4j
@Singleton
class GistUploader
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    @Inject
    public GistUploader(OkHttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    public void upload(String gistId, String token, String filename, String content)
    {
        if (gistId == null || gistId.isEmpty() || token == null || token.isEmpty())
        {
            log.debug("Gist upload skipped: no gist id or token configured");
            return;
        }

        JsonObject file = new JsonObject();
        file.addProperty("content", content);

        JsonObject files = new JsonObject();
        files.add(filename, file);

        JsonObject body = new JsonObject();
        body.add("files", files);

        Request request = new Request.Builder()
                .url("https://api.github.com/gists/" + gistId)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Accept", "application/vnd.github+json")
                .patch(RequestBody.create(JSON, body.toString()))
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Gist upload failed", e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    if (!r.isSuccessful())
                    {
                        log.warn("Gist upload returned {}", r.code());
                    }
                    else
                    {
                        log.debug("Gist updated");
                    }
                }
            }
        });
    }
}
