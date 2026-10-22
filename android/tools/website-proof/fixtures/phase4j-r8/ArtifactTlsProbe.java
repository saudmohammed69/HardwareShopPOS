import javax.net.ssl.HttpsURLConnection;
import java.net.URI;

/** Public build-artifact connectivity only; default certificate verification. */
class ArtifactTlsProbe {
    public static void main(String[] args) throws Exception {
        var url=URI.create("https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/8.6.1/com.android.application.gradle.plugin-8.6.1.pom").toURL();
        var connection=(HttpsURLConnection)url.openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
        System.out.println("HTTP="+connection.getResponseCode());
        System.out.println("cipher="+connection.getCipherSuite());
        connection.getInputStream().close();connection.disconnect();
    }
}
