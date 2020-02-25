package org.apache.helix.zookeeper.util;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.helix.msdcommon.constant.MetadataStoreRoutingConstants;
import org.apache.helix.msdcommon.datamodel.MetadataStoreRoutingData;
import org.apache.helix.msdcommon.datamodel.TrieRoutingData;
import org.apache.helix.msdcommon.exception.InvalidRoutingDataException;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultBackoffStrategy;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;


public class HttpRoutingDataReader {
  private static final String MSDS_ENDPOINT =
      System.getProperty(MetadataStoreRoutingConstants.MSDS_SERVER_ENDPOINT_KEY);
  private static final int HTTP_TIMEOUT_IN_MS = 5000;

  /** Double-checked locking requires that the following fields be volatile */
  private static volatile Map<String, List<String>> _rawRoutingData;
  private static volatile MetadataStoreRoutingData _metadataStoreRoutingData;

  /**
   * This class is a Singleton.
   */
  private HttpRoutingDataReader() {
  }

  /**
   * Fetches routing data from the data source via HTTP.
   * @return a mapping from "metadata store realm addresses" to lists of
   * "metadata store sharding keys", where the sharding keys in a value list all route to
   * the realm address in the key disallows a meaningful mapping to be returned
   */
  public static Map<String, List<String>> getRawRoutingData() throws IOException {
    if (MSDS_ENDPOINT == null || MSDS_ENDPOINT.isEmpty()) {
      throw new IllegalStateException(
          "HttpRoutingDataReader was unable to find a valid MSDS endpoint String in System Properties!");
    }
    if (_rawRoutingData == null) {
      synchronized (HttpRoutingDataReader.class) {
        if (_rawRoutingData == null) {
          String routingDataJson = getAllRoutingData();
          // Update the reference if reading routingData over HTTP is successful
          _rawRoutingData = parseRoutingData(routingDataJson);
        }
      }
    }
    return _rawRoutingData;
  }

  /**
   * Returns the routing data read from MSDS in a MetadataStoreRoutingData format.
   * @return
   * @throws IOException if there is an issue connecting to MSDS
   * @throws InvalidRoutingDataException if the raw routing data is not valid
   */
  public static MetadataStoreRoutingData getMetadataStoreRoutingData()
      throws IOException, InvalidRoutingDataException {
    if (_metadataStoreRoutingData == null) {
      synchronized (HttpRoutingDataReader.class) {
        if (_metadataStoreRoutingData == null) {
          _metadataStoreRoutingData = new TrieRoutingData(getRawRoutingData());
        }
      }
    }
    return _metadataStoreRoutingData;
  }

  /**
   * Makes an HTTP call to fetch all routing data.
   * @return
   * @throws IOException
   */
  private static String getAllRoutingData() throws IOException {
    // Note that MSDS_ENDPOINT should provide high-availability - it risks becoming a single point of failure if it's backed by a single IP address/host
    // Retry count is 3 by default.
    HttpGet requestAllData = new HttpGet(
        MSDS_ENDPOINT + MetadataStoreRoutingConstants.MSDS_GET_ALL_ROUTING_DATA_ENDPOINT);

    // Define timeout configs
    RequestConfig config = RequestConfig.custom().setConnectTimeout(HTTP_TIMEOUT_IN_MS)
        .setConnectionRequestTimeout(HTTP_TIMEOUT_IN_MS).setSocketTimeout(HTTP_TIMEOUT_IN_MS)
        .build();

    try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(config)
        .setConnectionBackoffStrategy(new DefaultBackoffStrategy())
        .setRetryHandler(new DefaultHttpRequestRetryHandler()).build()) {
      // Return the JSON because try-resources clause closes the CloseableHttpResponse
      HttpEntity entity = httpClient.execute(requestAllData).getEntity();
      if (entity == null) {
        throw new IOException("Response's entity is null!");
      }
      return EntityUtils.toString(entity, "UTF-8");
    }
  }

  /**
   * Returns the raw routing data in a Map< ZkRealm, List of shardingKeys > format.
   * @param routingDataJson
   * @return
   */
  private static Map<String, List<String>> parseRoutingData(String routingDataJson)
      throws IOException {
    if (routingDataJson != null) {
      @SuppressWarnings("unchecked")
      Map<String, Object> resultMap = new ObjectMapper().readValue(routingDataJson, Map.class);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> routingDataList =
          (List<Map<String, Object>>) resultMap.get(MetadataStoreRoutingConstants.ROUTING_DATA);
      @SuppressWarnings("unchecked")
      Map<String, List<String>> routingData = routingDataList.stream().collect(Collectors.toMap(
          realmKeyPair -> (String) realmKeyPair
              .get(MetadataStoreRoutingConstants.SINGLE_METADATA_STORE_REALM),
          mapEntry -> (List<String>) mapEntry.get(MetadataStoreRoutingConstants.SHARDING_KEYS)));
      return routingData;
    }
    return Collections.emptyMap();
  }
}