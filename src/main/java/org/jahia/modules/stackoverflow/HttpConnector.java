package org.jahia.modules.stackoverflow;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

/**    
 * HTTP connection helper to deal with distinct connection for distinct environments
 */
public class HttpConnector {
    Logger logger = LoggerFactory.getLogger(HttpConnector.class);

    private HttpHost targetHost;
    private CloseableHttpClient client;
    private HttpClientContext context;

    private String hostName;
    private String scheme;
    private String userName;
    private String password;
    private int port;

    private StringBuilder errorMessage;

    private static final String GET_FAILURE_MESSAGE = "GET failed for ";
    private static final String POST_FAILURE_MESSAGE = "POST failed for ";
    private static final String ERROR_GENERIC_REQUEST = "Generic error accessing ";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";

    public String getErrorMessage() {
        return errorMessage.toString();
    }

    public void setErrorMessage(String message) {
        errorMessage.append("</br>" + message);
    }

    /**     
     * Constructor for HttpConnectionHelper
     * @param hostName  Name of the host
     * @param scheme    http/https
     * @param port      Port number
     * @param userName  User credentials
     * @param password  Password credentials
     */
    public HttpConnector(String hostName, String scheme, int port, String userName, String password) {
        this.hostName = hostName;
        this.scheme = scheme;
        this.userName = userName;
        this.password = password;
        this.port = port;
        this.errorMessage = new StringBuilder();
    }

    private void prepareConnection() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        if (this.scheme.equalsIgnoreCase(HTTPS_SCHEME)) {
            this.targetHost = new HttpHost(hostName, port, HTTPS_SCHEME);
        } else if (this.scheme.equalsIgnoreCase(HTTP_SCHEME)) {
            this.targetHost = new HttpHost(hostName, port, HTTP_SCHEME);
        }

        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(userName, password));

        AuthCache authCache = new BasicAuthCache();
        authCache.put(targetHost, new BasicScheme());

        this.context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        context.setAuthCache(authCache);

        TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
        SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslContext,
                NoopHostnameVerifier.INSTANCE);

        Registry<ConnectionSocketFactory> socketFactoryRegistry =
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register(HTTPS_SCHEME, sslsf)
                        .register(HTTP_SCHEME, new PlainConnectionSocketFactory())
                        .build();

        BasicHttpClientConnectionManager connectionManager =
                new BasicHttpClientConnectionManager(socketFactoryRegistry);

        this.client = HttpClients.custom().setSSLSocketFactory(sslsf)
                .setConnectionManager(connectionManager).build();
    }

    /**     
     * Execute a GET request to URI and manipulate results and exceptions
     * @param uri   String containing the URI
     * @return String containing the request response; otherwise null
     */
    public String executeGetRequest(String uri) {

        try {
            prepareConnection();
            HttpGet httpGet = new HttpGet(uri);
            HttpResponse response = client.execute(targetHost, httpGet, context);

            switch (response.getStatusLine().getStatusCode()) {
                case HttpStatus.SC_OK:
                    return EntityUtils.toString(response.getEntity());
                case HttpStatus.SC_UNAUTHORIZED:
                    setErrorMessage("Connection to " + targetHost + "/" + uri + " returned unauthorized error. Please check your parameters");
                    return null;
                case HttpStatus.SC_BAD_REQUEST:
                    setErrorMessage("Request to " + targetHost + "/" + uri + " returned bad request error. Please check your request parameters");
                    return null;
                case HttpStatus.SC_FORBIDDEN:
                    setErrorMessage("Access forbidden to " + targetHost + "/" + uri);
                    return null;
                case HttpStatus.SC_INTERNAL_SERVER_ERROR:
                    setErrorMessage("Internal server error: " + targetHost + "/" + uri);
                    return null;
                default:
                    setErrorMessage(ERROR_GENERIC_REQUEST + targetHost + "/" + uri + "Please check the instance details");
                    return null;
            }

        } catch (KeyStoreException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(GET_FAILURE_MESSAGE + targetHost + "/" + uri + " with KeyStoreException");
        } catch (NoSuchAlgorithmException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(GET_FAILURE_MESSAGE + targetHost + "/" + uri + " with NoSuchAlgorithmException");
        } catch (KeyManagementException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(GET_FAILURE_MESSAGE + targetHost + "/" + uri + " with KeyManagementException");
        } catch (ClientProtocolException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(GET_FAILURE_MESSAGE + targetHost + "/" + uri + " with ClientProtocolException");
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(GET_FAILURE_MESSAGE + targetHost + "/" + uri + " with IOException");
        }

        setErrorMessage(ERROR_GENERIC_REQUEST + targetHost + " Please check the instance details");
        return null;
    }

    /**     
     * Execute a POST request to URI and manipulate results and exceptions
     * @param uri           String containing the URI
     * @param multipart     multipart object to http connection
     * @return String containing the request response; otherwise null
     */
    public String executePostRequest(String uri, HttpEntity multipart) {
        try {
            prepareConnection();
            HttpPost httpPost = new HttpPost(uri);
            httpPost.setEntity(multipart);
            HttpResponse response = client.execute(targetHost, httpPost, context);

            switch (response.getStatusLine().getStatusCode()) {
                case HttpStatus.SC_OK:
                    return EntityUtils.toString(response.getEntity());
                case HttpStatus.SC_UNAUTHORIZED:
                    setErrorMessage("Connection to " + targetHost + "/" + uri + " returned unauthorized error. Please check the instance details");
                    return null;
                case HttpStatus.SC_BAD_REQUEST:
                    setErrorMessage("Request to " + targetHost + "/" + uri + " returned bad request error. Please check your request parameters");
                    return null;
                case HttpStatus.SC_FORBIDDEN:
                    setErrorMessage("Access forbidden to " + targetHost + "/" + uri);
                    return null;
                case HttpStatus.SC_INTERNAL_SERVER_ERROR:
                    setErrorMessage("Internal server error: " + targetHost + "/" + uri);
                    return null;
                default:
                    setErrorMessage(ERROR_GENERIC_REQUEST + targetHost + "/" + uri);
                    return null;
            }
        } catch (KeyStoreException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(POST_FAILURE_MESSAGE + targetHost + "/" + uri + " with KeyStoreException");
        } catch (NoSuchAlgorithmException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(POST_FAILURE_MESSAGE + targetHost + "/" + uri + " with NoSuchAlgorithmException");
        } catch (KeyManagementException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(POST_FAILURE_MESSAGE + targetHost + "/" + uri + " with KeyManagementException");
        } catch (ClientProtocolException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(POST_FAILURE_MESSAGE + targetHost + "/" + uri + " with ClientProtocolException");
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            setErrorMessage(POST_FAILURE_MESSAGE + targetHost + "/" + uri + " with IOException");
        }

        setErrorMessage(ERROR_GENERIC_REQUEST + targetHost + "/" + uri);
        return null;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }
}