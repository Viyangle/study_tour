package com.viyangle.study_tour.utils;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 从高德 adcode Excel 导入 region 表，并自动补齐“虚拟市”以统一成省-市-县三级。
 *
 * 使用示例:
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.RegionExcelImporter"
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.RegionExcelImporter" -Dexec.args="--truncate=false --password=123456"
 */
public class RegionExcelImporter {

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);

        List<RawRegionRow> rawRows = readRawRows(config.excelPath);
        if (rawRows.isEmpty()) {
            throw new IllegalStateException("Excel 数据为空: " + config.excelPath.toAbsolutePath());
        }

        List<RegionNode> regionNodes = buildRegionNodes(rawRows);

        ImportResult result = importToMysql(config, regionNodes);
        System.out.printf(
                Locale.ROOT,
                "导入完成: total=%d, province=%d, city=%d, county=%d, virtualCity=%d, insertedOrUpdated=%d%n",
                regionNodes.size(),
                result.provinceCount,
                result.cityCount,
                result.countyCount,
                result.virtualCityCount,
                result.affectedRows
        );
    }

    private static ImportResult importToMysql(Config config, List<RegionNode> regionNodes) throws SQLException {
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl, config.username, config.password)) {
            conn.setAutoCommit(false);

            Set<String> columns = listColumns(conn, config.tableName);
            ensureRequiredColumns(columns, config.tableName);

            if (config.truncate) {
                clearTableBestEffort(conn, config.tableName);
            }

            List<String> insertColumns = pickInsertColumns(columns);
            String sql = buildUpsertSql(config.tableName, insertColumns);

            int affected = 0;
            int provinceCount = 0;
            int cityCount = 0;
            int countyCount = 0;
            int virtualCityCount = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (RegionNode node : regionNodes) {
                    bind(ps, insertColumns, node);
                    ps.addBatch();

                    if (node.level == 1) {
                        provinceCount++;
                    } else if (node.level == 2) {
                        cityCount++;
                        if (node.virtualCity) {
                            virtualCityCount++;
                        }
                    } else if (node.level == 3) {
                        countyCount++;
                    }
                }
                int[] batch = ps.executeBatch();
                for (int c : batch) {
                    if (c > 0) {
                        affected += c;
                    }
                }
            }

            conn.commit();
            return new ImportResult(affected, provinceCount, cityCount, countyCount, virtualCityCount);
        }
    }

    private static void clearTableBestEffort(Connection conn, String tableName) {
        try (PreparedStatement ps = conn.prepareStatement("TRUNCATE TABLE " + tableName)) {
            ps.execute();
            System.out.println("已执行 TRUNCATE TABLE " + tableName);
            return;
        } catch (SQLException e) {
            System.out.println("TRUNCATE 失败，将尝试 DELETE: " + e.getMessage());
        }

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tableName)) {
            int rows = ps.executeUpdate();
            System.out.println("已执行 DELETE FROM " + tableName + "，删除行数: " + rows);
        } catch (SQLException e) {
            System.out.println("DELETE 也失败，将跳过清空并继续 UPSERT: " + e.getMessage());
        }
    }

    private static Set<String> listColumns(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(conn.getCatalog(), null, tableName, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        if (!columns.isEmpty()) {
            return columns;
        }
        try (ResultSet rs = metaData.getColumns(conn.getCatalog(), null, tableName.toUpperCase(Locale.ROOT), null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static void ensureRequiredColumns(Set<String> columns, String tableName) {
        List<String> required = List.of("adcode", "name", "level", "parent_adcode");
        for (String col : required) {
            if (!columns.contains(col)) {
                throw new IllegalStateException("表 " + tableName + " 缺少必要字段: " + col);
            }
        }
    }

    private static List<String> pickInsertColumns(Set<String> actualColumns) {
        List<String> supported = List.of("adcode", "name", "level", "parent_adcode", "citycode", "is_virtual", "has_children");
        List<String> picked = new ArrayList<>();
        for (String c : supported) {
            if (actualColumns.contains(c)) {
                picked.add(c);
            }
        }
        return picked;
    }

    private static String buildUpsertSql(String tableName, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableName).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cols.get(i));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        sb.append(") ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (String col : cols) {
            if ("adcode".equals(col)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(col).append("=VALUES(").append(col).append(")");
            first = false;
        }
        return sb.toString();
    }

    private static void bind(PreparedStatement ps, List<String> cols, RegionNode node) throws SQLException {
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            int idx = i + 1;
            switch (col) {
                case "adcode" -> ps.setString(idx, node.adcode);
                case "name" -> ps.setString(idx, node.name);
                case "level" -> ps.setInt(idx, node.level);
                case "parent_adcode" -> ps.setString(idx, node.parentAdcode);
                case "citycode" -> ps.setString(idx, node.citycode);
                case "is_virtual" -> ps.setInt(idx, node.virtualCity ? 1 : 0);
                case "has_children" -> ps.setInt(idx, node.hasChildren ? 1 : 0);
                default -> throw new IllegalStateException("不支持字段: " + col);
            }
        }
    }

    private static List<RegionNode> buildRegionNodes(List<RawRegionRow> rawRows) {
        Map<String, RawRegionRow> rawByAdcode = new LinkedHashMap<>();
        for (RawRegionRow row : rawRows) {
            if (!isAdcode(row.adcode)) {
                continue;
            }
            if ("100000".equals(row.adcode)) {
                continue;
            }
            rawByAdcode.put(row.adcode, row);
        }

        Map<String, RegionNode> provinces = new LinkedHashMap<>();
        Map<String, RegionNode> cities = new LinkedHashMap<>();
        Map<String, RegionNode> counties = new LinkedHashMap<>();

        for (RawRegionRow row : rawByAdcode.values()) {
            if (isProvinceCode(row.adcode)) {
                provinces.put(row.adcode, new RegionNode(row.adcode, row.name, 1, null, normalizeCitycode(row.citycode), false));
            }
        }

        for (RawRegionRow row : rawByAdcode.values()) {
            if (isCityCode(row.adcode)) {
                String parentAdcode = row.adcode.substring(0, 2) + "0000";
                cities.put(row.adcode, new RegionNode(row.adcode, row.name, 2, parentAdcode, normalizeCitycode(row.citycode), false));
            }
        }

        for (RawRegionRow row : rawByAdcode.values()) {
            if (!isCountyCode(row.adcode)) {
                continue;
            }
            String provinceAdcode = row.adcode.substring(0, 2) + "0000";
            String cityAdcode = row.adcode.substring(0, 4) + "00";
            RegionNode cityNode = cities.get(cityAdcode);
            if (cityNode == null) {
                RawRegionRow provinceRaw = rawByAdcode.get(provinceAdcode);
                String provinceName = provinceRaw != null ? provinceRaw.name : provinceAdcode;
                String cityName = provinceName;
                String virtualCitycode = normalizeCitycode(provinceRaw != null ? provinceRaw.citycode : row.citycode);
                cityNode = new RegionNode(cityAdcode, cityName, 2, provinceAdcode, virtualCitycode, true);
                cities.put(cityAdcode, cityNode);
            }
            counties.put(row.adcode, new RegionNode(row.adcode, row.name, 3, cityAdcode, normalizeCitycode(row.citycode), false));
        }

        Map<String, Integer> childCount = new HashMap<>();
        for (RegionNode c : cities.values()) {
            childCount.merge(c.parentAdcode, 1, Integer::sum);
        }
        for (RegionNode d : counties.values()) {
            childCount.merge(d.parentAdcode, 1, Integer::sum);
        }

        List<RegionNode> all = new ArrayList<>();
        all.addAll(provinces.values());
        all.addAll(cities.values());
        all.addAll(counties.values());

        for (RegionNode n : all) {
            n.hasChildren = childCount.getOrDefault(n.adcode, 0) > 0;
        }

        all.sort(Comparator.comparing(a -> a.adcode));
        return all;
    }

    private static List<RawRegionRow> readRawRows(Path xlsxPath) throws Exception {
        if (!Files.exists(xlsxPath)) {
            throw new IllegalStateException("Excel 文件不存在: " + xlsxPath.toAbsolutePath());
        }

        try (ZipFile zip = new ZipFile(xlsxPath.toFile())) {
            List<String> sharedStrings = readSharedStrings(zip);
            List<Map<String, String>> rows = readSheetRows(zip, sharedStrings);
            List<RawRegionRow> result = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String name = trimToNull(row.get("A"));
                String adcode = trimToNull(row.get("B"));
                String citycode = trimToNull(row.get("C"));
                if (name == null || adcode == null) {
                    continue;
                }
                if ("adcode".equalsIgnoreCase(adcode)) {
                    continue;
                }
                result.add(new RawRegionRow(name, adcode, citycode));
            }
            return result;
        }
    }

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        Document doc = parseXml(zip.getInputStream(entry));
        NodeList siList = doc.getElementsByTagNameNS("*", "si");
        List<String> list = new ArrayList<>(siList.getLength());
        for (int i = 0; i < siList.getLength(); i++) {
            Node si = siList.item(i);
            list.add(readNodeText(si));
        }
        return list;
    }

    private static List<Map<String, String>> readSheetRows(ZipFile zip, List<String> sharedStrings) throws Exception {
        ZipEntry entry = zip.getEntry("xl/worksheets/sheet1.xml");
        if (entry == null) {
            throw new IllegalStateException("xlsx 中不存在 sheet1: xl/worksheets/sheet1.xml");
        }
        Document doc = parseXml(zip.getInputStream(entry));
        NodeList rowNodes = doc.getElementsByTagNameNS("*", "row");
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            NodeList cells = row.getElementsByTagNameNS("*", "c");
            Map<String, String> data = new LinkedHashMap<>();
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                String ref = cell.getAttribute("r");
                String col = parseCol(ref);
                if (col == null) {
                    continue;
                }
                String type = cell.getAttribute("t");
                String value = parseCellValue(cell, type, sharedStrings);
                data.put(col, value);
            }
            rows.add(data);
        }
        return rows;
    }

    private static Document parseXml(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(in);
    }

    private static String parseCellValue(Element cell, String type, List<String> sharedStrings) {
        if ("inlineStr".equals(type)) {
            NodeList is = cell.getElementsByTagNameNS("*", "is");
            if (is.getLength() == 0) {
                return null;
            }
            return trimToNull(readNodeText(is.item(0)));
        }

        NodeList values = cell.getElementsByTagNameNS("*", "v");
        if (values.getLength() == 0) {
            return null;
        }
        String raw = trimToNull(values.item(0).getTextContent());
        if (raw == null) {
            return null;
        }
        if ("s".equals(type)) {
            int idx = Integer.parseInt(raw);
            if (idx < 0 || idx >= sharedStrings.size()) {
                return null;
            }
            return trimToNull(sharedStrings.get(idx));
        }
        return raw;
    }

    private static String parseCol(String cellRef) {
        if (cellRef == null || cellRef.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = cellRef.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                sb.append(ch);
            } else {
                break;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String readNodeText(Node node) {
        NodeList all = ((Element) node).getElementsByTagNameNS("*", "t");
        if (all.getLength() == 0) {
            return trimToNull(node.getTextContent());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.getLength(); i++) {
            String t = all.item(i).getTextContent();
            if (t != null) {
                sb.append(t);
            }
        }
        return trimToNull(sb.toString());
    }

    private static boolean isAdcode(String adcode) {
        if (adcode == null || adcode.length() != 6) {
            return false;
        }
        for (int i = 0; i < adcode.length(); i++) {
            if (!Character.isDigit(adcode.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isProvinceCode(String adcode) {
        return adcode.endsWith("0000");
    }

    private static boolean isCityCode(String adcode) {
        return adcode.endsWith("00") && !adcode.endsWith("0000");
    }

    private static boolean isCountyCode(String adcode) {
        return !adcode.endsWith("00");
    }

    private static String normalizeCitycode(String citycode) {
        String v = trimToNull(citycode);
        if (v == null || "\\N".equals(v)) {
            return null;
        }
        return v;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static class RawRegionRow {
        private final String name;
        private final String adcode;
        private final String citycode;

        private RawRegionRow(String name, String adcode, String citycode) {
            this.name = name;
            this.adcode = adcode;
            this.citycode = citycode;
        }
    }

    private static class RegionNode {
        private final String adcode;
        private final String name;
        private final int level;
        private final String parentAdcode;
        private final String citycode;
        private final boolean virtualCity;
        private boolean hasChildren;

        private RegionNode(String adcode, String name, int level, String parentAdcode, String citycode, boolean virtualCity) {
            this.adcode = adcode;
            this.name = name;
            this.level = level;
            this.parentAdcode = parentAdcode;
            this.citycode = citycode;
            this.virtualCity = virtualCity;
        }
    }

    private static class ImportResult {
        private final int affectedRows;
        private final int provinceCount;
        private final int cityCount;
        private final int countyCount;
        private final int virtualCityCount;

        private ImportResult(int affectedRows, int provinceCount, int cityCount, int countyCount, int virtualCityCount) {
            this.affectedRows = affectedRows;
            this.provinceCount = provinceCount;
            this.cityCount = cityCount;
            this.countyCount = countyCount;
            this.virtualCityCount = virtualCityCount;
        }
    }

    private static class Config {
        private Path excelPath = Paths.get("src/main/resources/AMap_adcode_citycode.xlsx");
        private String jdbcUrl = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/study_tour");
        private String username = System.getenv().getOrDefault("DB_USER", "root");
        private String password = System.getenv().getOrDefault("DB_PASS", "");
        private String tableName = "region";
        private boolean truncate = true;

        private static Config fromArgs(String[] args) {
            Config config = new Config();
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--")) {
                    continue;
                }
                int idx = arg.indexOf('=');
                if (idx <= 2 || idx >= arg.length() - 1) {
                    continue;
                }
                String key = arg.substring(2, idx);
                String value = arg.substring(idx + 1);
                switch (key) {
                    case "excel" -> config.excelPath = Paths.get(value);
                    case "url" -> config.jdbcUrl = value;
                    case "username" -> config.username = value;
                    case "password" -> config.password = value;
                    case "table" -> config.tableName = value;
                    case "truncate" -> config.truncate = Boolean.parseBoolean(value);
                    default -> {
                        // ignore unknown option
                    }
                }
            }
            return config;
        }
    }
}
