/*
 * Copyright 2021 The Data-Portability Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.transfer.daybook.photos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import okhttp3.*;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.spi.cloud.storage.TemporaryPerJobDataStore;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.provider.ImportResult;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.types.common.models.photos.PhotoAlbum;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.photos.PhotosContainerResource;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

/** Imports albums and photos to Daybook */
public class DaybookPhotosImporter
    implements Importer<TokensAndUrlAuthData, PhotosContainerResource> {

  private final OkHttpClient client;
  private final ObjectMapper objectMapper;
  private final TemporaryPerJobDataStore jobStore;
  private final Monitor monitor;
  private final String baseUrl;

  public DaybookPhotosImporter(
      Monitor monitor,
      OkHttpClient client,
      ObjectMapper objectMapper,
      TemporaryPerJobDataStore jobStore,
      String baseUrl) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.jobStore = jobStore;
    this.monitor = monitor;
    this.baseUrl = baseUrl;

    monitor.debug(
          () -> String.format("Entered daybook auth: %s", baseUrl));
    
  }

  @Override
  public ImportResult importItem(
      UUID jobId,
      IdempotentImportExecutor executor,
      TokensAndUrlAuthData authData,
      PhotosContainerResource resource)
      throws Exception {
    if (resource == null) {
      // Nothing to import
      return ImportResult.OK;
    }
    // Import albums
    for (PhotoAlbum album : resource.getAlbums()) {
      executor.executeAndSwallowIOExceptions(
          album.getId(), album.getName(), () -> importAlbum(album, authData));
    }

    return new ImportResult(ImportResult.ResultType.OK);
  }

  private String importAlbum(PhotoAlbum album, TokensAndUrlAuthData authData) throws IOException {
    String description = album.getDescription();

    Request.Builder requestBuilder = new Request.Builder().url(baseUrl);
    requestBuilder.header("Authorization", "Bearer " + authData.getAccessToken());

    FormBody.Builder builder = new FormBody.Builder().add("title", album.getName());
    if (!Strings.isNullOrEmpty(description)) {
      builder.add("description", description);
    }
    RequestBody formBody = builder.build();
    requestBuilder.post(formBody);

    Response response = client.newCall(requestBuilder.build()).execute();
    int code = response.code();
    Preconditions.checkArgument(
        code >= 200 && code <= 299,
        String.format(
            "Error occurred in request for %s, code: %s, message: %s",
            baseUrl, code, response.message()));
    ResponseBody body = response.body();
    Preconditions.checkArgument(body != null, "Didn't get response body!");
    Map<String, Object> responseData = objectMapper.readValue(body.bytes(), Map.class);

    String newAlbumId = (String) ((Map<String, Object>) responseData.get("data")).get("id");
    if (Strings.isNullOrEmpty(newAlbumId)) {
      throw new IOException("Didn't receive new album id");
    }

    return newAlbumId;
  }
}