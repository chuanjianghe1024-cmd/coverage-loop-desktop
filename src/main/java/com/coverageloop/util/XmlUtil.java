package com.coverageloop.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** XML 解析工具（DOM，禁用外部实体） */
public final class XmlUtil {

    private XmlUtil() {
    }

    /** 解析 XML 字符串为根元素，失败返回 null */
    public static Element parse(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // 允许 DOCTYPE 声明（jacoco.xml 带 report.dtd），但绝不加载外部 DTD/实体
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 兜底：即使解析器尝试解析 DTD，也返回空源
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document document = builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            return document.getDocumentElement();
        } catch (Exception error) {
            return null;
        }
    }

    /** 直接子元素列表 */
    public static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        if (element == null) return result;
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) result.add((Element) node);
        }
        return result;
    }

    /** 第一个匹配的直接子元素，不存在返回 null */
    public static Element first(Element element, String name) {
        if (element == null) return null;
        for (Element child : children(element)) {
            if (child.getTagName().equals(name)) return child;
        }
        return null;
    }

    /** 递归查找第一个匹配的后代元素 */
    public static Element firstDeep(Element element, String name) {
        if (element == null) return null;
        if (element.getTagName().equals(name)) return element;
        for (Element child : children(element)) {
            Element found = firstDeep(child, name);
            if (found != null) return found;
        }
        return null;
    }

    /** 所有匹配的直接子元素 */
    public static List<Element> list(Element element, String name) {
        List<Element> result = new ArrayList<>();
        if (element == null) return result;
        for (Element child : children(element)) {
            if (child.getTagName().equals(name)) result.add(child);
        }
        return result;
    }

    /** 元素文本（含后代），无内容返回空串 */
    public static String text(Element element) {
        if (element == null) return "";
        return element.getTextContent() == null ? "" : element.getTextContent().trim();
    }

    /** 子元素文本，不存在返回空串 */
    public static String childText(Element element, String name) {
        Element child = first(element, name);
        return child == null ? "" : text(child);
    }

    public static String attr(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) return "";
        return element.getAttribute(name);
    }
}
