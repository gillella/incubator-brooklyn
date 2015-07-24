/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.location.jclouds;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.softlayer.compute.options.SoftLayerTemplateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.util.http.HttpTool;
import brooklyn.util.http.HttpToolResponse;
import brooklyn.util.text.Strings;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TwcBasicJcloudLocationCustomizer extends
        BasicJcloudsLocationCustomizer {

    private static final Logger LOG = LoggerFactory
            .getLogger(TwcBasicJcloudLocationCustomizer.class);

    public static final ConfigKey<Boolean> PRIVATE_NETWORK_ONLY = ConfigKeys
            .newBooleanConfigKey("privateNetworkOnly");
    public static final ConfigKey<Integer> VLAN_ID = ConfigKeys
            .newIntegerConfigKey("primaryNetworkComponentVlanId");
    public static final ConfigKey<Integer> BACKEND_VLAN_ID = ConfigKeys
            .newIntegerConfigKey("primaryBackendNetworkComponentVlanId");
    public static final ConfigKey<String> POST_INSTALL_SCRIPT_URI = ConfigKeys
            .newStringConfigKey("postInstallScriptUri");
    public static final ConfigKey<String> USER_DATA = ConfigKeys
            .newStringConfigKey("userData");
    public static final ConfigKey<String> DISK_TYPE = ConfigKeys
            .newStringConfigKey("diskType");

    public static final ConfigKey<Integer> DNSZONE = ConfigKeys
            .newIntegerConfigKey("dnsZoneId");

    // need to be delete after PR634 is merged
    public static final ConfigKey<String> DOMAIN = ConfigKeys
            .newStringConfigKey("domainName");

    @Override
    public void customize(JcloudsLocation location,
                          ComputeService computeService, TemplateOptions templateOptions) {
        if (templateOptions instanceof SoftLayerTemplateOptions) {
            SoftLayerTemplateOptions options = (SoftLayerTemplateOptions) templateOptions;
            Boolean privateNetworkOnly = location
                    .getConfig(PRIVATE_NETWORK_ONLY);
            if (privateNetworkOnly != null) {
                options.privateNetworkOnlyFlag(privateNetworkOnly);
            }

            options.domainName(location.getConfig(DOMAIN));

            Integer portSpeed = location.getConfig(ConfigKeys
                    .newIntegerConfigKey("portSpeed"));
            if (portSpeed != null)
                options.portSpeed(portSpeed);


            Integer vlanId = location.getConfig(VLAN_ID);
            if (vlanId != null) {
                options.primaryNetworkComponentNetworkVlanId(vlanId);
            }

            Integer backendVlanId = location.getConfig(BACKEND_VLAN_ID);
            if (backendVlanId != null) {
                options.primaryBackendNetworkComponentNetworkVlanId(backendVlanId);
            }

            String postInstallScriptUri = location
                    .getConfig(POST_INSTALL_SCRIPT_URI);
            if (!Strings.isEmpty(postInstallScriptUri)) {
                options.postInstallScriptUri(postInstallScriptUri);
            }

            String userData = location.getConfig(USER_DATA);
            if (!Strings.isEmpty(userData)) {
                options.userData(userData);
            }

            String diskType = location.getConfig(DISK_TYPE);
            if (!Strings.isEmpty(diskType)) {
                options.diskType(diskType);
            } else options.diskType("SAN");



        }
    }

    @Override
    public void customize(JcloudsLocation location,
                          ComputeService computeService, JcloudsSshMachineLocation machine) {


        String hostname = getHostname(machine);
        LOG.info(hostname);
        String ip = machine.getSubnetIp();
        Integer domainId = location.getConfig(DNSZONE);

        if (domainId != null) {
            URI baseUri = null;
            try {
                baseUri = new URI(location.getConfig(ConfigKeys
                        .newStringConfigKey("apiUrl"))
                        + "SoftLayer_Dns_Domain_ResourceRecord/createObject");

            } catch (URISyntaxException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            String data = "{\"parameters\" : " + "[{\"domainId\": " + domainId
                    + ",\"ttl\":60,\"host\":\"" + hostname + "\","
                    + "\"type\":\"A\",\"data\":\"" + ip + "\"}]}";

            // HttpClient client =
            // HttpTool.httpClientBuilder().credentials(credentials).uri(baseUri).build();
            Map<String, String> headers = new Hashtable<String, String>();
            headers.put("Accept", "application/json");
            HttpClient client = getDnsHttpClient(location);
            HttpToolResponse result = HttpTool.httpPost(client, baseUri,
                    headers, data.getBytes());
            LOG.info("HERE IS RESPONSE:" + result.getContentAsString());

        }

        String nsGroup = location.getConfig(ConfigKeys
                .newStringConfigKey("netscalerGroup"));
        if (nsGroup != null) {
            Integer port = location.getConfig(ConfigKeys
                    .newIntegerConfigKey("netscalerGroupPort"));
            HttpToolResponse result = addServerToNetscaler(hostname, ip,
                    location);
            LOG.info("HERE IS add server RESPONSE:"
                    + result.getContentAsString());
            if (result.getResponseCode() < 205) {
                HttpToolResponse result1 = bindServerToNetscaler(hostname,
                        nsGroup, port, location);
                LOG.info("HERE IS bind RESPONSE:"
                        + result1.getContentAsString());
            }

            if (result.getResponseCode() < 205) {
                HttpToolResponse result1 = saveServerToNetscaler(hostname,
                        nsGroup, port, location);
                LOG.info("HERE IS save RESPONSE:"
                        + result1.getContentAsString());
            }
        }

    }

    @Override
    public void preRelease(JcloudsSshMachineLocation machine) {
        Integer domainId = machine.getParent().getConfig(DNSZONE);
        if (domainId != null) {
            try {

                URI baseUriGet = new URI(machine.getParent().getConfig(
                        ConfigKeys.newStringConfigKey("apiUrl"))
                        + "SoftLayer_Dns_Domain/"
                        + machine.getParent().getConfig(DNSZONE)
                        + "/getResourceRecords?objectMask=id;host");

                HttpClient client = getDnsHttpClient(machine.jcloudsParent);
                HttpToolResponse result = HttpTool.httpGet(client, baseUriGet,
                        ImmutableMap.<String, String> of());

                JsonParser parser = new JsonParser();

                JsonArray resultJasonArray = parser.parse(
                        result.getContentAsString()).getAsJsonArray();

                String hostname = getHostname(machine);



                LOG.info(resultJasonArray.get(0).toString());
                Iterator<JsonElement> iter = resultJasonArray.iterator();
                while (iter.hasNext()) {
                    JsonElement element = iter.next();
                    LOG.info(element.toString());
                    JsonObject obj = element.getAsJsonObject();
                    LOG.info(obj.get("host").toString());
                    if (obj.get("host").toString()
                            .equals("\"" + hostname + "\"")) {
                        int id = obj.get("id").getAsInt();
                        LOG.info("ID: " + id);
                        URI baseUriDelete = new URI(
                                machine.getParent()
                                        .getConfig(
                                                ConfigKeys
                                                        .newStringConfigKey("apiUrl"))
                                        + "SoftLayer_Dns_Domain_ResourceRecord/"
                                        + id);

                        result = HttpTool.httpDelete(client, baseUriDelete,
                                ImmutableMap.<String, String> of());
                        LOG.info("DELETE RESULT for " + id + ": " + result);
                    }

                }

            } catch (URISyntaxException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }
        String nsGroup = machine.getParent().getConfig(
                ConfigKeys.newStringConfigKey("netscalerGroup"));
        if (nsGroup != null) {
            String hostname = getHostname(machine);
            String ip = machine.getSubnetIp();
            Integer port = machine.getParent().getConfig(
                    ConfigKeys.newIntegerConfigKey("netscalerGroupPort"));

            HttpToolResponse result = unBindServerFromNetscaler(hostname,
                    nsGroup, port, machine.getParent());
            LOG.info("HERE IS add server RESPONSE:"
                    + result.getContentAsString());
            if (result.getResponseCode() < 205) {
                HttpToolResponse result1 = deleteServerFromNetscaler(hostname,
                        ip, machine.getParent());
                LOG.info("HERE IS bind RESPONSE:"
                        + result1.getContentAsString());
            }
        }
    }

    @Override
    public void postRelease(JcloudsSshMachineLocation machine) {
        LOG.info("POST_RELEASE +++++++++++++++++++++++++++++");
    }

    private HttpClient getDnsHttpClient(JcloudsLocation location) {
        String user = location.getConfig(ConfigKeys
                .newStringConfigKey("identity"));
        String apiKey = location.getConfig(ConfigKeys
                .newStringConfigKey("credential"));
        String baseUri = location.getConfig(ConfigKeys
                .newStringConfigKey("apiUrl"));

        Credentials credentials = new UsernamePasswordCredentials(user, apiKey);
        return HttpTool.httpClientBuilder().credentials(credentials)
                .uri(baseUri).build();

    }

    private HttpClient getNetscalerHttpClient(JcloudsLocation location) {

        String baseUri = location.getConfig(ConfigKeys
                .newStringConfigKey("netscalerUrl"));


        return HttpTool.httpClientBuilder().uri(baseUri).build();

    }

    private HttpToolResponse addServerToNetscaler(String name, String ip,
                                                  JcloudsLocation location) {
        HttpToolResponse result = null;
        try {
            String baseUri = location.getConfig(ConfigKeys
                    .newStringConfigKey("netscalerUrl"));
            URI nsUri = new URI(baseUri + "config/server?action=add");
            Map<String, String> headers = new Hashtable<String, String>();
            headers.put("Content-Type",
                    "application/vnd.com.citrix.netscaler.server+json");
            HttpClient client = getNetscalerHttpClient(location);
            String data = "{\"server\":{\"name\":\"" + name
                    + "\",\"ipaddress\":\"" + ip + "\"}}";
            result = HttpTool.httpPost(client, nsUri, headers, data.getBytes());

        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return result;

    }

    private HttpToolResponse bindServerToNetscaler(String name, String group,
                                                   Integer port, JcloudsLocation location) {
        HttpToolResponse result = null;
        try {
            String baseUri = location.getConfig(ConfigKeys
                    .newStringConfigKey("netscalerUrl"));
            URI nsUri = new URI(baseUri
                    + "config/servicegroup_servicegroupmember_binding/" + group
                    + "?action=bind");
            Map<String, String> headers = new Hashtable<String, String>();
            headers.put(
                    "Content-Type",
                    "application/vnd.com.citrix.netscaler.servicegroup_servicegroupmember_binding+json");
            HttpClient client = getNetscalerHttpClient(location);
            String data = "{\"servicegroup_servicegroupmember_binding\":{\"servicegroupname\":\""
                    + group
                    + "\",\"servername\":\""
                    + name
                    + "\",\"port\":\""
                    + port + "\"}}";
            result = HttpTool.httpPost(client, nsUri, headers, data.getBytes());

        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return result;

    }

    private HttpToolResponse saveServerToNetscaler(String name, String group,
                                                   Integer port, JcloudsLocation location) {
        HttpToolResponse result = null;
        try {
            String baseUri = location.getConfig(ConfigKeys
                    .newStringConfigKey("netscalerUrl"));
            URI nsUri = new URI(baseUri
                    + "config/nsconfig?action=save");
            Map<String, String> headers = new Hashtable<String, String>();
            headers.put(
                    "Content-Type",
                    "application/vnd.com.citrix.netscaler.nsconfig+json");
            HttpClient client = getNetscalerHttpClient(location);
            String data = "{'nsconfig':{}}";
            result = HttpTool.httpPost(client, nsUri, headers, data.getBytes());

        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return result;

    }

    private HttpToolResponse deleteServerFromNetscaler(String name, String ip,
                                                       JcloudsLocation location) {
        HttpToolResponse result = null;
        try {
            String baseUri = location.getConfig(ConfigKeys
                    .newStringConfigKey("netscalerUrl"));
            URI nsUri = new URI(baseUri + "config/server/" + name);
            Map<String, String> headers = new Hashtable<String, String>();
            headers.put("Content-Type",
                    "application/vnd.com.citrix.netscaler.server+json");
            HttpClient client = getNetscalerHttpClient(location);

            result = HttpTool.httpDelete(client, nsUri, headers);

        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return result;

    }

    private HttpToolResponse unBindServerFromNetscaler(String name,
                                                       String group, Integer port, JcloudsLocation location) {
        HttpToolResponse result = null;
        try {
            String baseUri = location.getConfig(ConfigKeys
                    .newStringConfigKey("netscalerUrl"));
            URI nsUri = new URI(baseUri
                    + "config/servicegroup_servicegroupmember_binding/" + group
                    + "?action=unbind");
            Map<String, String> headers = new Hashtable<String, String>();
            headers.put(
                    "Content-Type",
                    "application/vnd.com.citrix.netscaler.servicegroup_servicegroupmember_binding+json");
            HttpClient client = getNetscalerHttpClient(location);
            String data = "{\"servicegroup_servicegroupmember_binding\":{\"servicegroupname\":\""
                    + group
                    + "\",\"servername\":\""
                    + name
                    + "\",\"port\":\""
                    + port + "\"}}";
            result = HttpTool.httpPost(client, nsUri, headers, data.getBytes());

        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return result;

    }

    private String getHostname(JcloudsSshMachineLocation machine) {
        String hostname = machine.getHostname();

        hostname = hostname.substring(0, hostname.length()
                - machine.getParent().getConfig(DOMAIN).length());


        if (hostname.substring(hostname.length() - 1).startsWith("."))
            hostname = hostname.substring(0, hostname.length() - 1);

        return hostname;
    }

}
