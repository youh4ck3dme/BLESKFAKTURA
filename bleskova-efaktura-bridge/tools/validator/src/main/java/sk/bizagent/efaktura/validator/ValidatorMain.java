package sk.bizagent.efaktura.validator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Offline UBL XSD + EN16931/Peppol Schematron runner.
 * Schemas are loaded only from the pinned local rules directory.
 * Remote schema/DTD fetches are disabled.
 */
public final class ValidatorMain {
    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 20000;

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: ValidatorMain <xml-file> <rules-root> [cache-dir]");
            System.exit(2);
        }
        try {
            Path rules = Path.of(args[1]);
            Path cache = args.length == 3 ? Path.of(args[2]) : rules.resolve("cache");
            String json = validate(Path.of(args[0]), rules, cache);
            System.out.write(json.getBytes(StandardCharsets.UTF_8));
            System.out.println();
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            System.out.println("{\"engineError\":" + quote(msg) + "}");
            System.exit(3);
        }
    }

    static String validate(Path xml, Path rules, Path cache) throws Exception {
        byte[] bytes = Files.readAllBytes(xml);
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> security = securityIssues(text);
        if (!security.isEmpty()) {
            return report("INVALID", security, "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), sha256(bytes));
        }
        if (exceedsStructure(text)) {
            return report("INVALID", List.of("XML depth or node limit exceeded before parse"), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), sha256(bytes));
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document doc;
        try {
            doc = dbf.newDocumentBuilder().parse(xml.toFile());
        } catch (Exception ex) {
            return report("INVALID", List.of("XML parse rejected: " + safe(ex)), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), sha256(bytes));
        }
        int[] nodes = new int[] {0};
        if (tooDeep(doc.getDocumentElement(), 1, nodes)) {
            return report("INVALID", List.of("XML depth or node limit exceeded"), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), "UNAVAILABLE", List.of(), sha256(bytes));
        }
        Element root = doc.getDocumentElement();
        String local = root.getLocalName();
        Path xsd = null;
        if ("Invoice".equals(local)) {
            xsd = rules.resolve("ubl-2.1/xsdrt/maindoc/UBL-Invoice-2.1.xsd");
        } else if ("CreditNote".equals(local)) {
            xsd = rules.resolve("ubl-2.1/xsdrt/maindoc/UBL-CreditNote-2.1.xsd");
        }
        if (xsd == null) {
            return report("VALID", List.of(), "UNSUPPORTED", List.of("Root is not UBL Invoice or CreditNote"), "UNSUPPORTED", List.of(), "UNSUPPORTED", List.of(), sha256(bytes));
        }
        List<String> xsdIssues = new ArrayList<>();
        String xsdStatus = "VALID";
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            sf.setResourceResolver((type, namespaceURI, publicId, systemId, baseURI) -> {
                if (systemId != null && (systemId.startsWith("http://") || systemId.startsWith("https://"))) {
                    throw new RuntimeException("Remote schema blocked: " + systemId);
                }
                return null;
            });
            Schema schema = sf.newSchema(xsd.toFile());
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            List<String> found = new ArrayList<>();
            validator.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) { found.add(line(e)); }
                public void error(SAXParseException e) { found.add(line(e)); }
                public void fatalError(SAXParseException e) { found.add(line(e)); }
            });
            validator.validate(new DOMSource(doc));
            xsdIssues.addAll(found);
            if (!found.isEmpty()) xsdStatus = "INVALID";
        } catch (Exception ex) {
            xsdStatus = "UNAVAILABLE";
            xsdIssues.add(safe(ex));
        }
        String en = "UNAVAILABLE";
        String peppol = "UNAVAILABLE";
        List<String> enIssues = new ArrayList<>();
        List<String> peppolIssues = new ArrayList<>();
        if (!"INVALID".equals(xsdStatus) && "VALID".equals(xsdStatus)) {
            try {
                enIssues.addAll(schematron(doc, rules, cache, "peppol/CEN-EN16931-UBL.sch"));
                en = enIssues.isEmpty() ? "VALID" : "INVALID";
            } catch (Exception ex) {
                en = "UNAVAILABLE";
                enIssues.add(safe(ex));
            }
            try {
                peppolIssues.addAll(schematron(doc, rules, cache, "peppol/PEPPOL-EN16931-UBL.sch"));
                peppol = peppolIssues.isEmpty() ? "VALID" : "INVALID";
            } catch (Exception ex) {
                peppol = "UNAVAILABLE";
                peppolIssues.add(safe(ex));
            }
        }
        return report("VALID", List.of(), xsdStatus, xsdIssues, en, enIssues, peppol, peppolIssues, sha256(bytes));
    }

    private static List<String> securityIssues(String text) {
        String lower = text.toLowerCase();
        List<String> issues = new ArrayList<>();
        if (lower.contains("<!doctype")) issues.add("DTD is forbidden");
        if (lower.contains("<!entity")) issues.add("Entity declarations are forbidden");
        if (lower.contains("xinclude") || lower.contains("xi:include")) issues.add("XInclude is forbidden");
        return issues;
    }

    private static boolean tooDeep(Node node, int depth, int[] nodes) {
        if (node == null) return false;
        nodes[0]++;
        if (depth > MAX_DEPTH || nodes[0] > MAX_NODES) return true;
        NodeList list = node.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (tooDeep(list.item(i), depth + 1, nodes)) return true;
        }
        return false;
    }

    private static boolean exceedsStructure(String xml) {
        int depth = 0;
        int nodes = 0;
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) break;
            if (xml.startsWith("<!--", lt)) {
                int end = xml.indexOf("-->", lt + 4);
                if (end < 0) return true;
                i = end + 3;
                continue;
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                int end = xml.indexOf("]]>", lt + 9);
                if (end < 0) return true;
                i = end + 3;
                continue;
            }
            if (xml.startsWith("<?", lt)) {
                int end = xml.indexOf("?>", lt + 2);
                if (end < 0) return true;
                i = end + 2;
                continue;
            }
            if (xml.startsWith("<!", lt)) return true;
            if (xml.startsWith("</", lt)) {
                depth--;
                int end = xml.indexOf('>', lt + 2);
                if (end < 0) return true;
                i = end + 1;
                continue;
            }
            nodes++;
            if (nodes > MAX_NODES || depth + 1 > MAX_DEPTH) return true;
            int j = lt + 1;
            char quote = 0;
            boolean closed = false;
            while (j < n) {
                char ch = xml.charAt(j);
                if (quote != 0) {
                    if (ch == quote) quote = 0;
                    j++;
                    continue;
                }
                if (ch == '"' || ch == '\'') {
                    quote = ch;
                    j++;
                    continue;
                }
                if (ch == '>') {
                    closed = true;
                    break;
                }
                j++;
            }
            if (!closed) return true;
            boolean selfClosing = j > lt && xml.charAt(j - 1) == '/';
            if (!selfClosing) depth++;
            if (depth > MAX_DEPTH) return true;
            i = j + 1;
        }
        return false;
    }

    private static List<String> schematron(Document doc, Path rules, Path cacheDir, String schRelative) throws Exception {
        Path sch = rules.resolve(schRelative);
        Path cache = compiled(rules, cacheDir, sch);
        Processor processor = new Processor(false);
        processor.setConfigurationProperty("http://saxon.sf.net/feature/allowedProtocols", "");
        XsltCompiler compiler = processor.newXsltCompiler();
        XsltExecutable exec = compiler.compile(new StreamSource(cache.toFile()));
        XsltTransformer transformer = exec.load();
        transformer.setInitialContextNode(processor.newDocumentBuilder().wrap(doc));
        StringWriter out = new StringWriter();
        Serializer serializer = processor.newSerializer(out);
        transformer.setDestination(serializer);
        transformer.transform();
        return failedAsserts(out.toString());
    }

    private static Path compiled(Path rules, Path cacheDir, Path sch) throws Exception {
        byte[] raw = Files.readAllBytes(sch);
        String hash = sha256(raw).substring(0, 16);
        Files.createDirectories(cacheDir);
        Path xsl = cacheDir.resolve(sch.getFileName().toString() + "." + hash + ".xsl");
        if (Files.exists(xsl)) return xsl;
        Path pipeline = rules.resolve("schxslt/2.0/pipeline-for-svrl.xsl");
        Processor processor = new Processor(false);
        XsltCompiler compiler = processor.newXsltCompiler();
        XsltExecutable exec = compiler.compile(new StreamSource(pipeline.toFile()));
        XsltTransformer transformer = exec.load();
        transformer.setSource(new StreamSource(sch.toFile()));
        Serializer serializer = processor.newSerializer(xsl.toFile());
        transformer.setDestination(serializer);
        transformer.transform();
        return xsl;
    }

    private static List<String> failedAsserts(String svrl) {
        List<String> out = new ArrayList<>();
        int idx = 0;
        while (true) {
            int at = svrl.indexOf("<svrl:failed-assert", idx);
            if (at < 0) break;
            int id = svrl.indexOf("id=\"", at);
            String rule = "";
            if (id > 0 && id < at + 400) {
                int end = svrl.indexOf('"', id + 4);
                if (end > id) rule = svrl.substring(id + 4, end);
            }
            int text = svrl.indexOf('>', at);
            int close = svrl.indexOf("</", text);
            String message = "";
            if (text > 0 && close > text) {
                message = svrl.substring(text + 1, Math.min(close, text + 500)).replaceAll("<[^>]+>", "").trim();
            }
            if (!rule.isEmpty() || !message.isEmpty()) out.add(rule + " " + message.trim());
            idx = at + 13;
            if (out.size() > 50) break;
        }
        return out;
    }

    private static String line(SAXParseException e) {
        return "line " + e.getLineNumber() + ": " + e.getMessage();
    }

    private static String safe(Exception ex) {
        String m = ex.getMessage();
        if (m == null) return ex.getClass().getSimpleName();
        return m.length() > 500 ? m.substring(0, 500) : m;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] dig = MessageDigest.getInstance("SHA-256").digest(bytes);
        return HexFormat.of().formatHex(dig);
    }

    private static String report(String security, List<String> securityIssues, String xsd, List<String> xsdIssues,
                                 String en, List<String> enIssues, String peppol, List<String> peppolIssues, String sha) {
        return "{"
                + "\"sha256\":" + quote(sha) + ","
                + "\"xmlSecurity\":" + layer(security, securityIssues) + ","
                + "\"xsd\":" + layer(xsd, xsdIssues) + ","
                + "\"en16931\":" + layer(en, enIssues) + ","
                + "\"peppol\":" + layer(peppol, peppolIssues)
                + "}";
    }

    private static String layer(String status, List<String> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":").append(quote(status)).append(",\"issues\":[");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(issues.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
