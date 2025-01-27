/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.gfsh;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_ENABLED_COMPONENTS;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_ENDPOINT_IDENTIFICATION_ENABLED;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_KEYSTORE;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_KEYSTORE_PASSWORD;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_KEYSTORE_TYPE;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_PROTOCOLS;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_REQUIRE_AUTHENTICATION;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_TRUSTSTORE;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_TRUSTSTORE_PASSWORD;
import static org.apache.geode.distributed.ConfigurationProperties.SSL_TRUSTSTORE_TYPE;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.apache.geode.cache.ssl.CertStores;
import org.apache.geode.cache.ssl.CertificateBuilder;
import org.apache.geode.cache.ssl.CertificateMaterial;
import org.apache.geode.internal.UniquePortSupplier;
import org.apache.geode.test.junit.rules.gfsh.GfshRule;

public class GfshWithSslAcceptanceTest {
  private static final String CERTIFICATE_ALGORITHM = "SHA256withRSA";
  private static final int CERTIFICATE_EXPIRATION_IN_DAYS = 1;
  private static final String STORE_PASSWORD = "geode";
  private static final String STORE_TYPE = "jks";

  private final String startLocator;
  private final String connect;

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Rule
  public final GfshRule gfsh;

  private final File keyStoreFile;
  private final File trustStoreFile;
  private final File securityPropertiesFile;

  public GfshWithSslAcceptanceTest() throws IOException,
      GeneralSecurityException {
    gfsh = new GfshRule();

    final UniquePortSupplier portSupplier = new UniquePortSupplier();
    final int port = portSupplier.getAvailablePort();

    tempFolder.create();
    keyStoreFile = tempFolder.newFile();
    trustStoreFile = tempFolder.newFile();
    securityPropertiesFile = tempFolder.newFile();

    final String hostName = InetAddress.getLocalHost().getCanonicalHostName();
    generateKeyAndTrustStore(hostName, keyStoreFile, trustStoreFile);

    startLocator = format(
        "start locator --connect=false --http-service-port=0 --name=locator --bind-address=%s --port=%d --J=-Dgemfire.jmx-manager-port=%d --security-properties-file=%s",
        hostName, port, portSupplier.getAvailablePort(),
        securityPropertiesFile.getAbsolutePath());
    connect = format("connect --locator=%s[%d] --security-properties-file=%s", hostName, port,
        securityPropertiesFile.getAbsolutePath());
  }

  @Test
  public void gfshCanConnectViaSslWithEndpointIdentificationEnabled() throws IOException {
    generateSecurityProperties(true, securityPropertiesFile, keyStoreFile,
        trustStoreFile);

    gfsh.execute(startLocator);
    gfsh.execute(connect);
  }

  // @Test
  public void gfshCanConnectViaSslWithEndpointIdentificationDisabled() throws IOException {
    generateSecurityProperties(false, securityPropertiesFile, keyStoreFile,
        trustStoreFile);

    gfsh.execute(startLocator);
    gfsh.execute(connect);
  }

  public static void generateKeyAndTrustStore(final String hostName, final File keyStoreFile,
      final File trustStoreFile) throws IOException, GeneralSecurityException {
    final CertificateMaterial ca =
        new CertificateBuilder(CERTIFICATE_EXPIRATION_IN_DAYS, CERTIFICATE_ALGORITHM)
            .commonName("Test CA")
            .isCA()
            .generate();

    final CertificateMaterial certificate = new CertificateBuilder(CERTIFICATE_EXPIRATION_IN_DAYS,
        CERTIFICATE_ALGORITHM)
            .commonName(hostName)
            .issuedBy(ca)
            .sanDnsName(hostName)
            .generate();

    final CertStores store = new CertStores(hostName);
    store.withCertificate("geode", certificate);
    store.trust("ca", ca);

    store.createKeyStore(keyStoreFile.getAbsolutePath(), STORE_PASSWORD);
    store.createTrustStore(trustStoreFile.getAbsolutePath(), STORE_PASSWORD);
  }

  private static void generateSecurityProperties(final boolean endpointIdentificationEnabled,
      final File securityPropertiesFile, final File keyStoreFile, final File trustStoreFile)
      throws IOException {
    final Properties properties = new Properties();

    properties.setProperty(SSL_REQUIRE_AUTHENTICATION, valueOf(true));
    properties.setProperty(SSL_ENABLED_COMPONENTS, "all");
    properties.setProperty(SSL_ENDPOINT_IDENTIFICATION_ENABLED,
        valueOf(endpointIdentificationEnabled));
    properties.setProperty(SSL_PROTOCOLS, "any");

    properties.setProperty(SSL_KEYSTORE, keyStoreFile.getAbsolutePath());
    properties.setProperty(SSL_KEYSTORE_TYPE, STORE_TYPE);
    properties.setProperty(SSL_KEYSTORE_PASSWORD, STORE_PASSWORD);

    properties.setProperty(SSL_TRUSTSTORE, trustStoreFile.getAbsolutePath());
    properties.setProperty(SSL_TRUSTSTORE_TYPE, STORE_TYPE);
    properties.setProperty(SSL_TRUSTSTORE_PASSWORD, STORE_PASSWORD);

    properties.store(new FileWriter(securityPropertiesFile), null);
  }

}
