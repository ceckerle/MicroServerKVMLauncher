import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

public class MicroServerKVMLauncher {

    public static void main(String[] args) throws Exception {
        // System.setProperty("javax.net.debug", "all");
        Security.setProperty("jdk.tls.disabledAlgorithms", "");

        if (args.length != 3) {
            System.err.println("Usage: java -jar MicroServerKVMLauncher.jar <host> <user> <password>");
            System.exit(1);
        }

        MicroServerKVMLauncher kvmLauncher = new MicroServerKVMLauncher(args[0], args[1], args[2]);
        kvmLauncher.login();
        try {
            // TODO: better async logout
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("Logout");
                    kvmLauncher.logout();
                    System.out.println("Logout done");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
            Document jnlp = kvmLauncher.getKVMJNLP();
            kvmLauncher.launchJNLP(jnlp);
        } catch (Exception e) {
            kvmLauncher.logout();
            throw e;
        }
    }

    private final String host;
    private final String user;
    private final String password;

    public MicroServerKVMLauncher(String host, String user, String password) throws Exception {
        this.host = host;
        this.user = user;
        this.password = password;
        CookieHandler.setDefault(new CookieManager());
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        }}, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        });
    }

    public void login() throws Exception {
        HttpURLConnection conn = connect("/data/login");
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(("user=" + URLEncoder.encode(this.user, "UTF-8") + "&password=" + URLEncoder.encode(this.password, "UTF-8")).getBytes(StandardCharsets.UTF_8));
        os.close();

        // <root> <status>ok</status> <authResult>0</authResult> <forwardUrl>index.html</forwardUrl> </root>
        // authResult 0: ok
        // authResult 5: session limit reached

        Document result = readXML(conn);
        XPathExpression authResultXpath = XPathFactory.newInstance().newXPath().compile("/root/authResult/text()");
        Integer authResult = Integer.parseInt(authResultXpath.evaluate(result));
        switch (authResult) {
            case 0:
                // success
                break;
            case 1:
                throw new Exception("Login failed: Invalid user or password");
            case 5:
                throw new Exception("Login failed: Session limit reached");
            default:
                throw new Exception("Login failed: Error code " + authResult);
        }
    }

    public void logout() throws Exception {
        HttpURLConnection conn = connect("/data/logout");
        int result = conn.getResponseCode();
        System.out.println(result);
    }

    public Document getKVMJNLP() throws Exception {
        HttpURLConnection conn = connect("/viewer.jnlp(" + URLEncoder.encode(this.host, "UTF-8") + "@0@" + System.currentTimeMillis() + ")");
        return readXML(conn);
    }

    // TODO: support virtual media
    public Document getMediaJNLP() throws Exception {
        HttpURLConnection conn = connect("/data?type=jnlp&get=vmStart(" + URLEncoder.encode(this.host, "UTF-8") + "@0@" + System.currentTimeMillis() + ")");
        return readXML(conn);
    }

    public void launchJNLP(Document jnlp) throws Exception {
        XPathExpression mainXpath = XPathFactory.newInstance().newXPath().compile("/jnlp/application-desc/@main-class");
        String main = mainXpath.evaluate(jnlp);
        System.out.println("Main Class: " + main);

        XPathExpression jarXpath = XPathFactory.newInstance().newXPath().compile("/jnlp/resources/jar");
        NodeList jars = (NodeList) jarXpath.evaluate(jnlp, XPathConstants.NODESET);
        List<URL> jarList = new ArrayList<>();
        for (int i = 0; i < jars.getLength(); i++) {
            Element node = (Element) jars.item(i);
            jarList.add(new URL(node.getAttribute("href")));
        }
        System.out.println("Class Path: " + jarList);

        XPathExpression argumentsXpath = XPathFactory.newInstance().newXPath().compile("/jnlp/application-desc/argument/text()");
        NodeList arguments = (NodeList) argumentsXpath.evaluate(jnlp, XPathConstants.NODESET);
        List<String> argList = new ArrayList<>();
        for (int i = 0; i < arguments.getLength(); i++) {
            Node node = arguments.item(i);
            argList.add(node.getNodeValue());
        }
        // argList.add("DEBUG=1");
        System.out.println("Arguments: " + argList);

        ClassLoader classLoader = new URLClassLoader(jarList.toArray(new URL[jarList.size()]), MicroServerKVMLauncher.class.getClassLoader());
        Class mainClass = classLoader.loadClass(main);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, new Object[]{argList.toArray(new String[argList.size()])});
    }

    private HttpURLConnection connect(String path) throws IOException {
        return (HttpURLConnection) new URL("https://" + URLEncoder.encode(this.host, "UTF-8") + path).openConnection();
    }

    private static Document readXML(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(e);
        }
    }

}
