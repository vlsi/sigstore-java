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
package dev.sigstore.json;

import com.google.gson.*;
import dev.sigstore.rekor.client.GsonAdaptersRekorEntry;
import dev.sigstore.rekor.client.GsonAdaptersRekorEntryBody;
import dev.sigstore.tuf.model.*;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * Supplies a Gson with custom byte to base64 serialization, and no html escaping. This instance of
 * GSON is NOT html/url safe, but makes more sense if you want to do things for the serialization of
 * requests between sigstore and this client -- and should probably be used for any api call to
 * sigstore that expects JSON.
 */
public enum GsonSupplier implements Supplier<Gson> {
  GSON;

  private final Gson gson =
      new GsonBuilder()
          .registerTypeAdapter(byte[].class, new GsonByteArrayAdapter())
          .registerTypeAdapter(
              LocalDateTime.class,
              (JsonDeserializer<LocalDateTime>)
                  (json, type, jsonDeserializationContext) ->
                      ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString())
                          .toLocalDateTime())
          .registerTypeAdapterFactory(new GsonAdaptersRekorEntry())
          .registerTypeAdapterFactory(new GsonAdaptersRekorEntryBody())
          .registerTypeAdapterFactory(new GsonAdaptersRootMeta())
          .registerTypeAdapterFactory(new GsonAdaptersHashes())
          .registerTypeAdapterFactory(new GsonAdaptersTargetMeta())
          .registerTypeAdapterFactory(new GsonAdaptersSnapshotMeta())
          .registerTypeAdapterFactory(new GsonAdaptersSnapshot())
          .registerTypeAdapterFactory(new GsonAdaptersDelegationRole())
          .registerTypeAdapterFactory(new GsonAdaptersDelegations())
          .registerTypeAdapterFactory(new GsonAdaptersRootRole())
          .registerTypeAdapterFactory(new GsonAdaptersSignature())
          .registerTypeAdapterFactory(new GsonAdaptersKey())
          .registerTypeAdapterFactory(new GsonAdaptersRoot())
          .registerTypeAdapterFactory(new GsonAdaptersTargets())
          .registerTypeAdapter(
              LocalDateTime.class,
              (JsonDeserializer<LocalDateTime>)
                  (json, type, jsonDeserializationContext) ->
                      ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString())
                          .toLocalDateTime())
          .disableHtmlEscaping()
          .create();

  @Override
  public Gson get() {
    return gson;
  }
}
