const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const projectRoot = path.resolve(__dirname, "..");
const readmePath = path.join(projectRoot, "README.md");
const outputDir = path.join(projectRoot, "postman");
const collectionPath = path.join(
  outputDir,
  "study-tour.postman_collection.json",
);
const environmentPath = path.join(
  outputDir,
  "study-tour.postman_environment.json",
);

const source = fs.readFileSync(readmePath, "utf8").replace(/\r\n/g, "\n");
const lines = source.split("\n");

function stableUuid(value) {
  const hash = crypto.createHash("sha256").update(value).digest();
  hash[6] = (hash[6] & 0x0f) | 0x40;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.subarray(0, 16).toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join("-");
}

function parseHeading(line, index) {
  const match = line.match(/^(#{2,6})\s+(.+?)\s*$/);
  return match
    ? { index, level: match[1].length, text: match[2].trim() }
    : null;
}

const headings = lines
  .map((line, index) => parseHeading(line, index))
  .filter(Boolean);

function headingBefore(index, predicate = () => true) {
  let result = null;
  for (const heading of headings) {
    if (heading.index >= index) break;
    if (predicate(heading)) result = heading;
  }
  return result;
}

function cleanTitle(value) {
  return value
    .replace(/^\d+(?:\.\d+)*\.?\s*/, "")
    .replace(/[（(]前端对照[）)]/, "")
    .trim();
}

function endpointHeading(index) {
  return headingBefore(index, (heading) => heading.level >= 3);
}

function groupName(index) {
  const numberedGroup = headingBefore(
    index,
    (heading) => heading.level === 3 && /^4\.\d+\s/.test(heading.text),
  );
  const interfaceList = headings.find(
    (heading) => heading.level === 2 && /^4\.\s/.test(heading.text),
  );
  if (
    numberedGroup &&
    interfaceList &&
    numberedGroup.index > interfaceList.index
  ) {
    return numberedGroup.text;
  }

  const levelTwo = headingBefore(index, (heading) => heading.level === 2);
  return levelTwo ? cleanTitle(levelTwo.text) : "其他接口";
}

function sectionEnd(heading) {
  if (!heading) return lines.length;
  const next = headings.find(
    (candidate) =>
      candidate.index > heading.index && candidate.level <= heading.level,
  );
  return next ? next.index : lines.length;
}

function fencedBlocks(start, end) {
  const blocks = [];
  for (let index = start; index < end; index += 1) {
    const open = lines[index].match(/^```([A-Za-z0-9_-]*)\s*$/);
    if (!open) continue;
    const body = [];
    let close = index + 1;
    while (close < end && !/^```\s*$/.test(lines[close])) {
      body.push(lines[close]);
      close += 1;
    }
    blocks.push({
      start: index,
      end: Math.min(close, end),
      language: open[1].toLowerCase(),
      content: body.join("\n").trim(),
    });
    index = close;
  }
  return blocks;
}

function requestStateAt(start, index) {
  let state = null;
  for (let cursor = start; cursor < index; cursor += 1) {
    if (/响应(?:结果|示例|内容|中的|为|数据)?/.test(lines[cursor])) {
      state = "response";
    }
    if (/请求(?:示例|体|参数|字段|格式)?/.test(lines[cursor])) {
      state = "request";
    }
  }
  return state;
}

function parseHttpBlock(content) {
  const blockLines = content.split("\n");
  const firstLineIndex = blockLines.findIndex((line) => line.trim());
  if (firstLineIndex < 0) return null;

  const firstLine = blockLines[firstLineIndex].trim();
  const requestLine = firstLine.match(
    /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(\S+)/i,
  );
  if (!requestLine) return null;

  const headers = [];
  let bodyStart = -1;
  for (let index = firstLineIndex + 1; index < blockLines.length; index += 1) {
    const line = blockLines[index];
    if (!line.trim()) {
      bodyStart = index + 1;
      break;
    }
    const header = line.match(/^([^:]+):\s*(.*)$/);
    if (header) {
      headers.push({ key: header[1].trim(), value: header[2].trim() });
    }
  }

  const body =
    bodyStart >= 0 ? blockLines.slice(bodyStart).join("\n").trim() : "";
  return {
    method: requestLine[1].toUpperCase(),
    target: requestLine[2],
    headers,
    body,
  };
}

function splitTarget(target) {
  const withoutOrigin = target.replace(/^https?:\/\/[^/]+/i, "");
  const questionMark = withoutOrigin.indexOf("?");
  if (questionMark < 0) {
    return { pathname: withoutOrigin || "/", query: new URLSearchParams() };
  }
  return {
    pathname: withoutOrigin.slice(0, questionMark) || "/",
    query: new URLSearchParams(withoutOrigin.slice(questionMark + 1)),
  };
}

function normalizedEndpointKey(method, pathname) {
  return `${method.toUpperCase()} ${pathname
    .replace(/\{[^}]+\}/g, "{}")
    .replace(/:[A-Za-z][A-Za-z0-9_]*/g, "{}")}`;
}

function pathMatchesTemplate(template, actualPathname) {
  const escapedTemplate = template
    .replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
    .replace(/\\\{[^}]+\\\}/g, "[^/]+");
  return new RegExp(`^${escapedTemplate}$`).test(actualPathname);
}

function defaultQueryValue(name, description = "") {
  const documentedDefault = description.match(/默认\s*`([^`]+)`/);
  if (documentedDefault) return documentedDefault[1];

  const defaults = {
    accountId: "1",
    departureDateFrom: "2026-08-01",
    departureDateTo: "2026-08-31",
    hasLeader: "false",
    keyword: "南京",
    leaderAccountId: "3",
    message: "请规划一条适合研学的路线",
    onlyAvailable: "true",
    ownerAccountId: "1",
    pageNum: "1",
    pageSize: "10",
    parentAdcode: "110000",
    refreshToken: "{{refreshToken}}",
    regionCode: "320100",
    relation: "ALL",
    status: "OPEN",
    tag: "博物馆研学",
    withExplanation: "true",
  };
  return defaults[name] ?? "";
}

function queryDocumentation(start, end) {
  const result = new Map();
  let inParameterSection = false;

  for (let index = start; index < end; index += 1) {
    const line = lines[index];
    if (/^请求参数[：:]?\s*$/.test(line.trim())) {
      inParameterSection = true;
      continue;
    }
    if (
      inParameterSection &&
      (/^响应/.test(line.trim()) || /^#{2,6}\s/.test(line))
    ) {
      inParameterSection = false;
    }

    if (inParameterSection) {
      const parameter = line.match(/^- `([A-Za-z][A-Za-z0-9_]*)`[：:](.+)$/);
      if (parameter) {
        const description = parameter[2].trim();
        result.set(parameter[1], {
          description,
          required: /必填/.test(description),
          optional: /可选/.test(description),
        });
      }
    }

    const inlineParameters = line.match(/^- 参数[：:](.+)$/);
    if (inlineParameters) {
      const pattern = /`([A-Za-z][A-Za-z0-9_]*)`（([^）]*)）/g;
      let match;
      while ((match = pattern.exec(inlineParameters[1])) !== null) {
        result.set(match[1], {
          description: match[2],
          required: /必填/.test(match[2]),
          optional: !/必填/.test(match[2]),
        });
      }
    }

    const optionalQuery = line.match(
      /^- 可选查询参数[：:]\s*`([A-Za-z][A-Za-z0-9_]*)`[，,]?\s*(.*)$/,
    );
    if (optionalQuery) {
      result.set(optionalQuery[1], {
        description: optionalQuery[2].trim() || "可选查询参数",
        required: false,
        optional: true,
      });
    }
  }

  return result;
}

function queryParameters(start, end, method, pathname) {
  const docs = queryDocumentation(start, end);
  const values = new Map();
  const activeFromFirstExample = new Set();
  let matchingExampleIndex = 0;

  for (const block of fencedBlocks(start, end)) {
    if (
      block.language !== "http" ||
      requestStateAt(start, block.start) !== "request"
    ) {
      continue;
    }
    const parsed = parseHttpBlock(block.content);
    if (!parsed || parsed.method !== method) continue;
    const target = splitTarget(parsed.target);
    if (!pathMatchesTemplate(pathname, target.pathname)) {
      continue;
    }
    matchingExampleIndex += 1;
    for (const [key, value] of target.query.entries()) {
      if (!values.has(key)) values.set(key, value);
      if (matchingExampleIndex === 1) activeFromFirstExample.add(key);
    }
  }

  for (const [key, doc] of docs.entries()) {
    if (!values.has(key)) {
      values.set(key, defaultQueryValue(key, doc.description));
    }
  }

  return [...values.entries()].map(([key, value]) => {
    const doc = docs.get(key);
    const resolvedValue =
      key === "refreshToken"
        ? "{{refreshToken}}"
        : value || defaultQueryValue(key, doc?.description);
    const enabledByDefault =
      activeFromFirstExample.has(key) ||
      doc?.required ||
      key === "pageNum" ||
      key === "pageSize";
    const result = {
      key,
      value: resolvedValue,
    };
    if (doc?.description) result.description = doc.description;
    if (!enabledByDefault) result.disabled = true;
    return result;
  });
}

function requestJsonBody(start, end) {
  for (const block of fencedBlocks(start, end)) {
    if (
      block.language === "json" &&
      requestStateAt(start, block.start) === "request"
    ) {
      try {
        return JSON.stringify(JSON.parse(block.content), null, 2);
      } catch {
        // Some README examples may intentionally be partial. Keep looking for
        // the next valid JSON request example rather than importing bad JSON.
      }
    }
  }
  return null;
}

function descriptionFor(start, end) {
  for (let index = start; index < end; index += 1) {
    const match = lines[index].match(/^- 描述[：:](.+)$/);
    if (match) return match[1].trim();
  }
  return "";
}

function pathVariableDefault(name) {
  const values = {
    accountId: "1",
    id: "1",
    memoryId: "1",
    projectId: "1",
    routeId: "2",
    sessionId: "1",
  };
  return values[name] ?? "1";
}

function encodeQueryValue(value) {
  return encodeURIComponent(value)
    .replace(/%7B%7B/gi, "{{")
    .replace(/%7D%7D/gi, "}}");
}

function buildUrl(pathname, query = []) {
  const variables = [];
  const postmanPath = pathname.replace(/\{([^}]+)\}/g, (_, name) => {
    variables.push({
      key: name,
      value: pathVariableDefault(name),
      description: `README 路径参数：${name}`,
    });
    return `:${name}`;
  });

  const enabledQuery = query.filter((parameter) => !parameter.disabled);
  const rawQuery = enabledQuery.length
    ? `?${enabledQuery
        .map(
          ({ key, value }) =>
            `${encodeURIComponent(key)}=${encodeQueryValue(value)}`,
        )
        .join("&")}`
    : "";

  const url = {
    raw: `{{baseUrl}}${postmanPath}${rawQuery}`,
    host: ["{{baseUrl}}"],
    path: postmanPath.split("/").filter(Boolean),
  };
  if (query.length) url.query = query;
  if (variables.length) url.variable = variables;
  return url;
}

function isPublicPath(pathname) {
  return (
    pathname === "/register" ||
    pathname.startsWith("/register/") ||
    pathname === "/login" ||
    pathname.startsWith("/login/") ||
    pathname === "/regions" ||
    pathname.startsWith("/regions/")
  );
}

function endpointName(method, pathname, fallback) {
  const names = {
    "GET /chat/groups": "查询项目群列表（sessions 别名）",
    "POST /chat/groups": "创建或恢复项目群",
    "POST /chat/groups/{sessionId}/join": "加入项目群",
    "DELETE /chat/groups/{sessionId}": "删除项目群",
    "GET /projects/available": "可接项目",
    "GET /projects/mine": "我的项目",
    "POST /routes/{id}/favorite": "收藏路线",
    "DELETE /routes/{id}/favorite": "取消收藏路线",
  };
  return names[`${method} ${pathname}`] || fallback || `${method} ${pathname}`;
}

function buildRequestItem(endpoint) {
  const headers = [];
  let body;

  if (endpoint.pathname === "/upload" && endpoint.method === "POST") {
    body = {
      mode: "formdata",
      formdata: [
        {
          key: "image",
          type: "file",
          src: [],
          description: "选择需要上传的图片文件",
        },
      ],
    };
  } else if (endpoint.body) {
    headers.push({
      key: "Content-Type",
      value: "application/json",
      type: "text",
    });
    body = {
      mode: "raw",
      raw: endpoint.body,
      options: { raw: { language: "json" } },
    };
  }

  const request = {
    method: endpoint.method,
    header: headers,
    url: buildUrl(endpoint.pathname, endpoint.query),
    description: [
      endpoint.description,
      `来源：README.md 第 ${endpoint.sourceLine} 行`,
    ]
      .filter(Boolean)
      .join("\n\n"),
  };
  if (isPublicPath(endpoint.pathname)) request.auth = { type: "noauth" };
  if (body) request.body = body;

  const item = {
    name: endpoint.name,
    request,
    response: [],
  };

  if (endpoint.method === "POST" && endpoint.pathname === "/login") {
    item.event = [
      {
        listen: "test",
        script: {
          type: "text/javascript",
          exec: [
            "const result = pm.response.json();",
            "",
            "pm.test(\"登录成功并返回 Token\", function () {",
            "    pm.expect(result.code).to.eql(1);",
            "    pm.expect(result.data).to.have.property(\"token\");",
            "});",
            "",
            "if (result.code === 1 && result.data?.token) {",
            "    pm.collectionVariables.set(\"token\", result.data.token);",
            "    pm.environment.set(\"token\", result.data.token);",
            "}",
            "",
            "if (result.code === 1 && result.data?.refreshToken) {",
            "    pm.collectionVariables.set(\"refreshToken\", result.data.refreshToken);",
            "    pm.environment.set(\"refreshToken\", result.data.refreshToken);",
            "}",
          ],
        },
      },
    ];
  }

  return item;
}

const formalEndpoints = [];
for (let index = 0; index < lines.length; index += 1) {
  const methodMatch = lines[index].match(
    /^- 方法：`(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)`\s*$/i,
  );
  if (!methodMatch) continue;

  let pathname = null;
  for (
    let pathIndex = index + 1;
    pathIndex < Math.min(index + 7, lines.length);
    pathIndex += 1
  ) {
    const pathMatch = lines[pathIndex].match(/^- 路径：`([^`]+)`\s*$/);
    if (pathMatch) {
      pathname = pathMatch[1];
      break;
    }
  }
  if (!pathname) continue;

  const heading = endpointHeading(index);
  const end = sectionEnd(heading);
  const method = methodMatch[1].toUpperCase();
  const headingName = heading ? cleanTitle(heading.text) : null;
  const numbered = Boolean(heading && /^4\.\d+\.\d+\s/.test(heading.text));
  formalEndpoints.push({
    method,
    pathname,
    name: endpointName(method, pathname, headingName),
    group: groupName(index),
    description: descriptionFor(index, end),
    body: requestJsonBody(index, end),
    query: queryParameters(index, end, method, pathname),
    priority: numbered ? 30 : 20,
    sourceLine: index + 1,
    range: { start: heading?.index ?? index, end },
  });
}

function withinFormalEndpoint(index) {
  return formalEndpoints.some(
    (endpoint) =>
      index >= endpoint.range.start && index < endpoint.range.end,
  );
}

const supplementaryEndpoints = [];
for (const block of fencedBlocks(0, lines.length)) {
  if (block.language !== "http" || withinFormalEndpoint(block.start)) continue;
  const parsed = parseHttpBlock(block.content);
  if (!parsed) continue;

  const target = splitTarget(parsed.target);
  const query = [...target.query.entries()].map(([key, value]) => ({
    key,
    value,
  }));
  let body = null;
  if (parsed.body) {
    try {
      body = JSON.stringify(JSON.parse(parsed.body), null, 2);
    } catch {
      body = null;
    }
  }
  const heading = endpointHeading(block.start);
  supplementaryEndpoints.push({
    method: parsed.method,
    pathname: target.pathname,
    name: endpointName(
      parsed.method,
      target.pathname,
      heading ? cleanTitle(heading.text) : null,
    ),
    group: groupName(block.start),
    description: heading ? cleanTitle(heading.text) : "",
    body,
    query,
    priority: 10,
    sourceLine: block.start + 2,
  });
}

for (let index = 0; index < lines.length; index += 1) {
  if (withinFormalEndpoint(index)) continue;
  const inlinePattern =
    /`(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(\/[^`\s]+)`/gi;
  let match;
  while ((match = inlinePattern.exec(lines[index])) !== null) {
    const method = match[1].toUpperCase();
    const target = splitTarget(match[2]);
    supplementaryEndpoints.push({
      method,
      pathname: target.pathname,
      name: endpointName(method, target.pathname, null),
      group: groupName(index),
      description: lines[index].replace(/`/g, "").replace(/^-\s*/, "").trim(),
      body: null,
      query: [...target.query.entries()].map(([key, value]) => ({
        key,
        value,
      })),
      priority: 5,
      sourceLine: index + 1,
    });
  }
}

const selectedByKey = new Map();
for (const endpoint of [...formalEndpoints, ...supplementaryEndpoints]) {
  const key = normalizedEndpointKey(endpoint.method, endpoint.pathname);
  const existing = selectedByKey.get(key);
  if (!existing || endpoint.priority > existing.priority) {
    selectedByKey.set(key, endpoint);
  }
}

const selectedEndpoints = [...selectedByKey.values()].sort(
  (left, right) => left.sourceLine - right.sourceLine,
);
const folders = new Map();
for (const endpoint of selectedEndpoints) {
  if (!folders.has(endpoint.group)) folders.set(endpoint.group, []);
  folders.get(endpoint.group).push(buildRequestItem(endpoint));
}

const defaultServiceAddress =
  source.match(/默认服务地址：`([^`]+)`/)?.[1] ??
  "http://47.94.95.110:8080";
const sourceModifiedAt = fs.statSync(readmePath).mtime.toISOString();

const collection = {
  info: {
    _postman_id: stableUuid("study-tour-postman-collection"),
    name: "Study Tour API",
    description:
      "根据项目 README.md 自动生成。登录成功后会自动保存 token 和 refreshToken；除公开接口外，其他请求统一继承 Bearer Token。",
    schema:
      "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    _exporter_id: "codex-readme-generator",
  },
  auth: {
    type: "bearer",
    bearer: [{ key: "token", value: "{{token}}", type: "string" }],
  },
  event: [
    {
      listen: "prerequest",
      script: {
        type: "text/javascript",
        exec: [
          "if (!pm.variables.get(\"baseUrl\")) {",
          "    throw new Error(\"请先设置 baseUrl，或选择 Study Tour 环境\");",
          "}",
        ],
      },
    },
  ],
  variable: [
    { key: "baseUrl", value: defaultServiceAddress, type: "string" },
    { key: "token", value: "", type: "string" },
    { key: "refreshToken", value: "", type: "string" },
  ],
  item: [...folders.entries()].map(([name, item]) => ({ name, item })),
};

const environment = {
  id: stableUuid("study-tour-postman-environment"),
  name: "Study Tour",
  values: [
    {
      key: "baseUrl",
      value: defaultServiceAddress,
      type: "default",
      enabled: true,
    },
    { key: "token", value: "", type: "secret", enabled: true },
    { key: "refreshToken", value: "", type: "secret", enabled: true },
  ],
  _postman_variable_scope: "environment",
  _postman_exported_at: sourceModifiedAt,
  _postman_exported_using: "Codex README generator",
};

fs.mkdirSync(outputDir, { recursive: true });
fs.writeFileSync(collectionPath, `${JSON.stringify(collection, null, 2)}\n`);
fs.writeFileSync(environmentPath, `${JSON.stringify(environment, null, 2)}\n`);

// Fail fast if generation ever produces invalid JSON or duplicate endpoints.
JSON.parse(fs.readFileSync(collectionPath, "utf8"));
JSON.parse(fs.readFileSync(environmentPath, "utf8"));
if (selectedEndpoints.length !== selectedByKey.size) {
  throw new Error("Generated collection contains duplicate endpoints");
}

const requestBodyCount = selectedEndpoints.filter(
  (endpoint) => endpoint.body || endpoint.pathname === "/upload",
).length;
console.log(
  [
    `Generated ${selectedEndpoints.length} requests`,
    `in ${folders.size} folders`,
    `with ${requestBodyCount} request bodies`,
    `Collection: ${path.relative(projectRoot, collectionPath)}`,
    `Environment: ${path.relative(projectRoot, environmentPath)}`,
  ].join("\n"),
);
