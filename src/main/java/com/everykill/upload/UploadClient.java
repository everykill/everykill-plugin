/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.model.KillRecord;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the ingest server. Registration and kill upload, nothing else.
 *
 * <p>Every call goes out on {@code enqueue()} — the OkHttp thread pool — never the
 * client thread. Callers hand in a callback and get answered there.
 *
 * <p><b>Retry only on {@code 429}, {@code 5xx} and transport errors.</b> Everything else
 * is a per-record verdict inside a {@code 200}, and retrying a rejected record just
 * sends a broken row again forever.
 */
@Slf4j
public class UploadClient
{
	private static final MediaType JSON = MediaType.get("application/json");

	/**
	 * Hard caps from the contract, checked before we send rather than after a 413.
	 *
	 * <p>Two caps because a record count alone does not bound memory — a kill with a
	 * long drop list is much bigger than one without.
	 */
	private static final int MAX_RECORDS = 200;
	private static final int MAX_BYTES = 2 * 1024 * 1024;

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	UploadClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;

		// the wire needs lowercase enums; the injected Gson emits constant names.
		this.gson = UploadGson.wire(gson);
	}

	/**
	 * Trades a client id for a bearer token.
	 *
	 * <p>Idempotent on the server: the same client id returns a fresh working token
	 * rather than a 409, so a plugin that lost its token but kept its id recovers
	 * silently. {@code recoveryCode} is non-null on the FIRST registration only.
	 */
	public void register(String baseUrl, String clientId, Consumer<Registration> onDone,
		Consumer<String> onError)
	{
		final HttpUrl url = endpoint(baseUrl, "register");
		if (url == null)
		{
			onError.accept("Upload URL is not a valid address");
			return;
		}

		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId);

		final Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Could not reach the server: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody rb = response.body())
				{
					if (!response.isSuccessful() || rb == null)
					{
						onError.accept("Server said " + response.code());
						return;
					}

					final JsonObject json = gson.fromJson(rb.string(), JsonObject.class);
					if (json == null || !json.has("token"))
					{
						onError.accept("Server sent no token");
						return;
					}

					onDone.accept(new Registration(
						json.get("token").getAsString(),
						json.has("recoveryCode") && !json.get("recoveryCode").isJsonNull()
							? json.get("recoveryCode").getAsString()
							: null));
				}
				catch (IOException | RuntimeException e)
				{
					onError.accept("Could not read the reply: " + e.getMessage());
				}
			}
		});
	}

	/**
	 * Sends a batch.
	 *
	 * <p>{@code onDone} is handed {@code true} when the batch may leave the queue, and
	 * that includes rejections: accepted, duplicate and rejected are all terminal, so
	 * holding a rejected record back would park it at the head of the queue forever.
	 *
	 * <p>{@code onRetry} means keep the batch — {@code 429}, {@code 5xx}, or the
	 * connection failed. It carries the server's {@code retryAfter} in seconds when
	 * there is one, because honouring theirs beats inventing our own backoff.
	 */
	public void send(String baseUrl, String token, List<KillRecord> batch,
		Consumer<Result> onDone, Consumer<Integer> onRetry)
	{
		final HttpUrl url = endpoint(baseUrl, "kills");
		if (url == null)
		{
			log.debug("everykill: upload url is unusable, holding the batch");
			onRetry.accept(0);
			return;
		}

		if (batch.size() > MAX_RECORDS)
		{
			// should be impossible - PendingKills batches at 50 - but a 413 processes
			// nothing, so it is worth not finding out the expensive way.
			log.warn("everykill: batch of {} exceeds the {} cap", batch.size(), MAX_RECORDS);
			onRetry.accept(0);
			return;
		}

		final String payload = gson.toJson(batch);
		if (payload.length() > MAX_BYTES)
		{
			log.warn("everykill: batch is over the {} byte cap", MAX_BYTES);
			onRetry.accept(0);
			return;
		}

		final Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, payload))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("everykill: upload failed, keeping the batch", e);
				onRetry.accept(0);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody rb = response.body())
				{
					final int code = response.code();

					if (code == 429)
					{
						onRetry.accept(retryAfter(response));
						return;
					}

					if (code >= 500)
					{
						onRetry.accept(0);
						return;
					}

					if (code == 401 || code == 403)
					{
						// the token is dead. the batch stays queued and the caller
						// re-registers; the client id survives, so the history does.
						onDone.accept(Result.unauthorised());
						return;
					}

					if (!response.isSuccessful() || rb == null)
					{
						// 4xx that isn't auth: the request itself is wrong and
						// sending it again changes nothing.
						log.warn("everykill: upload rejected with {}", code);
						onDone.accept(Result.dropped());
						return;
					}

					final JsonObject json = gson.fromJson(rb.string(), JsonObject.class);

					// a whole batch rejected for one identical reason is a CLIENT
					// fault, not bad data. treating those as ordinary rejections
					// drains the entire history into nothing inside a 200 - which is
					// exactly what my UNCONTESTED enum bug would have done.
					final String systemic = systemicReason(json);
					if (systemic != null)
					{
						onDone.accept(Result.systemic(systemic));
						return;
					}

					onDone.accept(Result.of(
						value(json, "accepted"),
						value(json, "duplicate"),
						value(json, "rejected")));
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("everykill: could not read the upload reply", e);
					onRetry.accept(0);
				}
			}
		});
	}

	/** The server's systemic reason, or null when the batch was ordinary. */
	private static String systemicReason(JsonObject json)
	{
		if (json == null || !json.has("systemic") || json.get("systemic").isJsonNull())
		{
			return null;
		}

		final JsonObject systemic = json.getAsJsonObject("systemic");
		return systemic.has("reason") ? systemic.get("reason").getAsString() : "client fault";
	}

	/**
	 * Publishes or withdraws the display name on public leaderboards.
	 *
	 * <p><b>The only call that ever carries an account name.</b> Deliberately separate
	 * from {@link #send}: a name on the kill batch would ride along on every upload,
	 * and "did we send a name" has to be answerable by reading one method rather than
	 * auditing every path into a batch.
	 *
	 * <p>A null name withdraws. That deletes the name server-side rather than hiding
	 * it — a {@code published = false} column with the name still in it is the version
	 * that leaks.
	 */
	public void publish(String baseUrl, String token, String displayName,
		String accountType, Consumer<String> onDone, Consumer<String> onError)
	{
		final HttpUrl url = endpoint(baseUrl, "publish");
		if (url == null)
		{
			onError.accept("Upload address is not a valid URL");
			return;
		}

		final JsonObject body = new JsonObject();
		body.addProperty("publish", displayName != null);
		if (displayName != null)
		{
			body.addProperty("displayName", displayName);
		}

		// omitted entirely when withheld, rather than sent as "hidden". a field the
		// server never receives cannot be logged, leaked or un-hidden later.
		if (accountType != null)
		{
			body.addProperty("accountType", accountType);
		}

		final Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Could not reach the server: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody rb = response.body())
				{
					if (!response.isSuccessful())
					{
						onError.accept("Server said " + response.code());
						return;
					}
					onDone.accept(displayName == null
						? "Name removed from leaderboards"
						: "Publishing as " + displayName);
				}
				catch (RuntimeException e)
				{
					onError.accept("Could not read the reply: " + e.getMessage());
				}
			}
		});
	}

	/**
	 * Everything the server holds about this account, as JSON. GDPR articles 15/20.
	 *
	 * <p>Handed back as raw text rather than parsed — the plugin writes it to a file
	 * and never needs to understand it, and parsing it would mean tracking a schema
	 * that is deliberately the server's business.
	 */
	public void export(String baseUrl, String token, Consumer<String> onDone,
		Consumer<String> onError)
	{
		me(baseUrl, token, "GET", onDone, onError);
	}

	/**
	 * Erases the account, its kills, drops and tokens. GDPR article 17.
	 *
	 * <p>Irreversible and keeps no tombstone, so the caller must have asked first.
	 */
	public void erase(String baseUrl, String token, Consumer<String> onDone,
		Consumer<String> onError)
	{
		me(baseUrl, token, "DELETE", onDone, onError);
	}

	private void me(String baseUrl, String token, String method,
		Consumer<String> onDone, Consumer<String> onError)
	{
		final HttpUrl url = endpoint(baseUrl, "me");
		if (url == null)
		{
			onError.accept("Upload address is not a valid URL");
			return;
		}

		final Request.Builder builder = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token);

		final Request request = "DELETE".equals(method)
			? builder.delete().build()
			: builder.get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Could not reach the server: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody rb = response.body())
				{
					if (!response.isSuccessful() || rb == null)
					{
						onError.accept("Server said " + response.code());
						return;
					}
					onDone.accept(rb.string());
				}
				catch (IOException | RuntimeException e)
				{
					onError.accept("Could not read the reply: " + e.getMessage());
				}
			}
		});
	}

	private static int value(JsonObject json, String key)
	{
		return json != null && json.has(key) && !json.get(key).isJsonNull()
			? json.get(key).getAsInt()
			: 0;
	}

	/** Seconds the server asked us to wait, or 0 when it did not say. */
	private static int retryAfter(Response response)
	{
		final String header = response.header("Retry-After");
		if (header == null)
		{
			return 0;
		}

		try
		{
			return Integer.parseInt(header.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * Builds {@code <base>/v1/<path>}, or null when the configured URL is unusable.
	 *
	 * <p>Parsed rather than concatenated because the base is user-editable config, and
	 * a malformed URL should be a dead upload rather than a crash on the client thread.
	 */
	private static HttpUrl endpoint(String baseUrl, String path)
	{
		if (baseUrl == null || baseUrl.trim().isEmpty())
		{
			return null;
		}

		final HttpUrl base = HttpUrl.parse(baseUrl.trim());
		return base == null
			? null
			: base.newBuilder().addPathSegment("v1").addPathSegment(path).build();
	}

	/** A token, and the recovery code if this was the first registration. */
	public static final class Registration
	{
		public final String token;
		public final String recoveryCode;

		Registration(String token, String recoveryCode)
		{
			this.token = token;
			this.recoveryCode = recoveryCode;
		}
	}

	/** What the server did with a batch. */
	public static final class Result
	{
		public final int accepted;
		public final int duplicate;
		public final int rejected;
		public final boolean unauthorised;

		/**
		 * Set when every record failed for the same reason.
		 *
		 * <p>Inverts the normal rule: KEEP the batch, stop uploading, and say so. The
		 * records are fine; we are the broken part.
		 */
		public final String systemicReason;

		private Result(int accepted, int duplicate, int rejected, boolean unauthorised,
			String systemicReason)
		{
			this.accepted = accepted;
			this.duplicate = duplicate;
			this.rejected = rejected;
			this.unauthorised = unauthorised;
			this.systemicReason = systemicReason;
		}

		static Result of(int accepted, int duplicate, int rejected)
		{
			return new Result(accepted, duplicate, rejected, false, null);
		}

		static Result unauthorised()
		{
			return new Result(0, 0, 0, true, null);
		}

		static Result dropped()
		{
			return new Result(0, 0, 0, false, null);
		}

		static Result systemic(String reason)
		{
			return new Result(0, 0, 0, false, reason);
		}
	}
}
