# 部署与回滚说明

> 更新时间：2026-11-18　适用版本：`0.0.4`

## 1. 当前部署拓扑

| 项目 | 当前值 |
|---|---|
| SSH 别名 | `Server` |
| 主机 | `47.94.95.110` |
| 应用目录 | `/root` |
| systemd 服务 | `myapp.service` |
| JAR | `/root/study_tour-0.0.4-v2feedback.jar` |
| 外部配置 | `/root/application.yaml` |
| 环境变量 | `/root/.env`（`EnvironmentFile`，权限 `600`） |
| Java | 17 |
| MySQL / Redis | Docker 容器 `mysql`、`redis-stack`，分别映射 3306、6379 |
| 公网 HTTP | `http://47.94.95.110:8080` 当前可访问 |
| 公网 HTTPS | `https://47.94.95.110:8443` 当前从外网超时，服务器本机可访问 |

单实例部署是当前任务存储、限流和会话锁的前提，不能直接扩成多 worker。

## 2. 常规发布

### 2.1 本地构建

```bash
mvn -B -ntp -Pci verify
mvn -B -ntp -Pci package -DskipTests
sha256sum target/study_tour-0.0.4.jar   # Git Bash；PowerShell 用 Get-FileHash
```

不要传播 `target/` 下的旧 JAR；以本次构建时间的产物为准。

### 2.2 备份远端 JAR 与数据库

```bash
TS=$(date +%Y%m%d_%H%M%S)
cp -p /root/study_tour-0.0.4-v2feedback.jar "/root/study_tour-0.0.4-v2feedback.jar.bak.${TS}"

docker exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
   --single-transaction --routines --triggers \
   --default-character-set=utf8mb4 study_tour' \
  | gzip > "/root/study_tour_backup_${TS}.sql.gz"

gzip -t "/root/study_tour_backup_${TS}.sql.gz"
```

口令只从容器环境变量读取，不写入命令历史或文档。生产发布前必须先备份数据库。

### 2.3 上传并切换 JAR

```bash
scp -o ServerAliveInterval=30 target/study_tour-0.0.4.jar \
  Server:/root/study_tour-0.0.4-v2feedback.jar.new

ssh Server 'sha256sum /root/study_tour-0.0.4-v2feedback.jar.new'
```

本地和远端 hash 一致后再切换：

```bash
ssh Server
mv /root/study_tour-0.0.4-v2feedback.jar.new /root/study_tour-0.0.4-v2feedback.jar
systemctl restart myapp
```

启动时 Flyway 自动执行 `db/migration` 中尚未应用的版本；当前最新为 V8。V8 的 `attraction_guides` 增量包含来源快照、同步状态和自动更新开关。

### 2.4 验证

```bash
systemctl is-active myapp
curl -fsSk https://127.0.0.1:8443/actuator/health

docker exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -D study_tour \
   -e "SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank"'

docker exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -D study_tour \
   -e "SELECT COUNT(*),SUM(status=\"PUBLISHED\") FROM attraction_guides"'
```

启动日志应包含 `Successfully applied ... migrations` 和 `Started StudyTourApplication`。首次部署讲解功能时，启动 5 分钟后会执行一次定时回填，`journalctl -u myapp` 可看到“景点讲解回填完成”。

也可以使用 JAR 内的独立回填工具，直接补齐讲解：

```bash
DB_PASS=$(docker exec mysql printenv MYSQL_ROOT_PASSWORD)
DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=study_tour \
DB_USERNAME=root DB_PASSWORD="$DB_PASS" \
GUIDE_AI_ENABLED=false \
java -Dloader.main=com.viyangle.study_tour.utils.AttractionGuideBackfillTool \
  -cp /root/study_tour-0.0.4-v2feedback.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

`GUIDE_AI_ENABLED=false` 只用于先验证资料链路；需要生成 AI 知识点/思考题时改为 `true`，并确认 `CHAT_MODEL_KEY` 有效。

## 3. 回滚

### 3.1 只回滚应用

```bash
systemctl stop myapp
cp -p /root/study_tour-0.0.4-v2feedback.jar.bak.<TS> \
  /root/study_tour-0.0.4-v2feedback.jar
systemctl start myapp
```

Flyway 默认 `validate-on-migrate=true`，新版本已执行的迁移不会因回退 JAR 自动撤销。若旧 JAR 不认识新表但新表只是新增且不影响旧代码，可先回退应用；若迁移改变了旧字段，必须同时恢复数据库。

### 3.2 数据库恢复

仅在确认新版本迁移不可兼容时执行：

```bash
systemctl stop myapp
gzip -dc /root/study_tour_backup_<TS>.sql.gz | \
  docker exec -i mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" study_tour'
systemctl start myapp
```

恢复前再次确认目标数据库名和备份文件，避免覆盖错误库。

## 4. 2026-11-18 发布记录

| 项目 | 值 |
|---|---|
| JAR SHA-256 | `8b1922d5d3ed0f1e8d3830e20e2265d23a8dea28e43d09d269a17db1aa3676f0` |
| 旧 JAR 备份 | `/root/study_tour-0.0.4-v2feedback.jar.bak.20261118_135312` |
| MySQL 备份 | `/root/study_tour_backup_20261118_135312.sql.gz` |
| Flyway | V7、V8 成功 |
| 健康检查 | 本机 `UP`；公网 8080 `/login/ping` 返回 `ok` |
| 讲解数据 | 63 个景点全部入库并发布：来源 43、父景点 4、基础资料 16 |
| 定时回填 | 2026-11-18 13:59:22 执行，结果“没有待处理景点” |

## 5. 已知部署问题

### 5.1 外网 8443 超时

服务器本机 `https://127.0.0.1:8443/actuator/health` 正常，外网访问 8443 超时；公网 8080 正常。需要在阿里云安全组和服务器防火墙确认 8443/TCP 入方向规则。当前代码同时监听 8080 和 8443，配置中的 `redirectPort` 不等于所有 HTTP 请求会自动 302 到 HTTPS。

### 5.2 模型 Key 返回 401

本次重启时远端模型接口返回：

```text
401 Incorrect API key provided
```

这会导致 AI 路线、标签打标和讲解 AI 加工降级；景点同步和百科资料回填不受影响。处理方式：

1. 更新 `/root/.env` 中有效的 `CHAT_MODEL_KEY`（以及需要的模型服务地址）；
2. `systemctl restart myapp`；
3. 以 ADMIN 身份调用 `POST /admin/attraction-guides/resync?limit=100`，重新生成已发布讲解的知识点和思考题。

在 Key 修复前，`attraction_guides` 中的知识点和思考题是保守模板，不是模型生成内容。
