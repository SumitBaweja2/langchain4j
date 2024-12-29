package dev.langchain4j.alloydb.postgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static dev.langchain4j.internal.Utils.isNotNullOrBlank;
import static dev.langchain4j.internal.Utils.isNullOrBlank;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;


public class AlloyDBEngine {

    private final String alloydbInstanceName;
    private final String dbUser;
    private final String dbPass;
    private final String dbName;
    private final boolean alloydbEnableIAMAuth;
    private final HikariDataSource dataSource;

    public AlloyDBEngine(String alloydbInstanceName, String dbName, String dbUser, String dbPass)
            throws Exception {
        this.alloydbInstanceName = ensureNotBlank(alloydbInstanceName, "alloydbInstanceName");
        this.dbName = ensureNotBlank(dbName, "dbName");
        if (isNotNullOrBlank(dbUser) && isNotNullOrBlank(dbPass)) {
            this.alloydbEnableIAMAuth = false;
            this.dbUser = dbUser;
            this.dbPass = dbPass;
        } else {
            this.alloydbEnableIAMAuth = true;
            this.dbUser = getUser();
            this.dbPass = null;
        }
        this.dataSource = createDataSource();
    }

    private String getUser() throws Exception {

        try {
            // Retrieve the Application Default Credentials
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/userinfo.email");
            credentials.refreshIfExpired();
            // Check if credentials are service account credentials
            if (credentials instanceof com.google.auth.oauth2.ServiceAccountCredentials serviceAccountCredentials) {
                // Get the service account email
                String userEmail = serviceAccountCredentials.getClientEmail();
                if (isNullOrBlank(userEmail)) {
                    String accessToken = credentials.getAccessToken().getTokenValue();

                    // Query the UserInfo API
                    String userInfoEndpoint = "https://www.googleapis.com/oauth2/v3/userinfo";
                    HttpRequestFactory requestFactory =
                            new NetHttpTransport().createRequestFactory();
                    HttpResponse response = requestFactory
                            .buildGetRequest(new GenericUrl(userInfoEndpoint))
                            .setHeaders(new HttpHeaders().setAuthorization("Bearer " + accessToken))
                            .execute();
                    String jsonResponse = response.parseAsString();
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode userInfo = objectMapper.readTree(jsonResponse);
                    return userInfo.get("email").asText();

                }
                return userEmail;

            } else {
                throw new Exception("Default credentials are not a service account.");
            }
        } catch (IOException e) {
            throw new Exception(e.getMessage());
        }

    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql:///%s", this.dbName));
        if (!alloydbEnableIAMAuth) {
            config.setUsername(this.dbUser);
            config.setPassword(this.dbPass);
        }
        config.addDataSourceProperty("socketFactory", "com.google.cloud.alloydb.SocketFactory");
        config.addDataSourceProperty("alloydbInstanceName", this.alloydbInstanceName);
        config.addDataSourceProperty("alloydbEnableIAMAuth", alloydbEnableIAMAuth);
        return new HikariDataSource(config);
    }


    /**
     * Gets connection to alloydb.
     * 
     * @return connection to database
     * @throws SQLException
     */
    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

}
