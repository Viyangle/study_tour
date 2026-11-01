# 模型驱动的路线 Agent

更新日期：2026-11-01。

`POST /routes/agent/runs` 的后台执行已从固定检索/规划流程改为 AgentLoop。
原有路线生成、优化和发布接口不受影响；草稿只保存在本轮内存中，不自动写入正式路线。

## 职责

- 模型：理解原始需求，判断信息是否齐全，选择工具，编辑站点、交通与时间，判断预算、步行、开放时间等业务条件是否满足。
- 后端：执行模型选择的工具，检查参数类型、数值、时间格式/顺序及 POI/日期引用，管理任务、账号隔离、调用额度、取消和超时。
- 后端不自动补齐缺失需求、不选择景点、不切换交通方式、不计算或重排日程，不以数据合法性校验替代业务可行性判断。
- `feasible`、`issues`、费用及每日汇总由模型提供；未知数字保留 null。

## 调用过程

```text
AgentRunController.start()
  → AgentRunExecutor.submit() / execute()
  → AgentLoop.run()
      → 系统说明 + 分角色历史 + 结构化上下文 + 本轮用户原文
      → 模型返回 answer / plan，或原生工具调用
      → AgentToolExecutor 参数校验并调用工具
      → 按 tool call ID 将工具结果交回模型
      → 模型继续决定下一步
  → 写入任务结果，前端按原接口轮询
```

工具与草稿每轮独立创建。客户端在后续请求中继续传 `history`、`constraints`、`currentRoute`；
服务端不会把中间草稿写成“上一版已接受路线”，也不会复用其他账号的编辑状态。

## 阶段性文本通知

模型在每个实际工作阶段开始时用一句简短文本通知用户，例如“我先筛选符合历史研学方向的景点。”，
进入交通阶段时再说明“接下来比较各站之间的交通方式。”。阶段由模型按任务决定，不构成固定流程。
同阶段内连续工具调用、重试或多轮交互不重复播报；工作目标变化或返回之前阶段开展新调整时才再次说明。

阶段文本放在助手消息 content 中，格式为 `{"kind":"progress","stage":"places","message":"我先筛选景点。"}`，
可与阶段首次原生工具调用同时返回。后续调用可以没有文本；单独返回 progress 也会继续循环，不结束任务。
同阶段使用相同 stage 标识，服务端按当前 stage 去重，不按工具名推断阶段。
进度通过现有 `events` 返回，`tool=null`、`label=message`、`status=running`；先记录阶段说明，再执行本阶段工具。
它不是 `result.kind`，前端无需将 progress 当作最终回答或放入后续 history。
普通问答直接回答，缺失信息时直接调用 ask_user；同批有 ask_user 时不额外展示开始工作的阶段说明。

## 工具

| 名称 | 参数 | 行为 |
| --- | --- | --- |
| `search_places` | query、city?、limit? | RAG 检索，返回景点事实和文本片段 |
| `search_graph_places` | tags、regionCode?、city?、limit? | 图谱候选查询 |
| `get_place_details` | poiIds | 数据库核实 POI；不存在时查高德 |
| `search_reference_pairs` | tag?、pageNum?、pageSize? | 查询参考景点组合 |
| `calculate_leg` | fromPoiId、toPoiId、mode | 有方向的地图查询；结果按方向/方式在本轮缓存，不自动改草稿 |
| `get_route` | 无 | 读取草稿 |
| `update_constraints` | constraints | 合并模型提供的非 null 约束字段 |
| `clear_constraints` | fields | 按用户明确要求清除旧约束，例如预算不限时清除 budget |
| `edit_route` | stops | 完整有序站点列表替换草稿，实现增删、替换、重排；清空旧交通与日程 |
| `edit_transport` | legs | 完整交通段列表替换草稿；不自动查询、切换或估算 |
| `edit_schedule` | days | 完整逐日日程替换草稿；到达、离开、休息、研学目标和汇总由模型提供 |
| `validate_itinerary` | 无 | 检查当前草稿的数据合法性及引用完整性 |
| `ask_user` | message、questions | 终止型工具：直接返回追问并结束本轮 |

`edit_route` 的 stops 每项含 `poiId`、`recommendedDuration`（分钟）、可选 `notes`。
`edit_transport` 使用 API 中的 Leg 结构，mode 为 walking/transit/driving。
`edit_schedule` 使用 DaySummary 和 ScheduleItem 结构；每天包含 day、date、schedule 及模型提供的汇总。
定位出发点可用 `__origin__` 引用，前提是请求提供了 origin。

编辑工具按参数提交，不会修改模型决定的顺序、交通方式、时长或日期。
路线编辑会使旧交通/日程失效，模型需要重新提交相应数据。
完成 plan 前检查每个路线 POI 均已核实、日程覆盖路线、交通引用对应日期。
后端不会因缺少必去点、超预算、超步行上限等业务问题自动拒绝或改写模型方案；这些由模型判断和说明。

## 缺失信息与 ask_user

生成/编辑研学方案前，模型检查当前任务需要的 tag/兴趣、预算、城市、天数与可用时间等上下文。
必要信息缺失、含糊或冲突时，应立即调用 `ask_user`，合并询问缺失项；不先继续搜索、查询交通或编辑。
明确表示“预算不限”“主题由你推荐”属于用户已提供相应决定；普通地点查询不强制收集与查询无关的字段。
缺失判断只由模型完成，后端不使用规则列表或关键词识别代替模型。

例如模型调用：

```json
{
  "name": "ask_user",
  "arguments": {
    "message": "还需要确认研学方向和预算。",
    "questions": ["希望偏重哪个研学 tag？", "每人预算是多少，或是否不限预算？"]
  }
}
```

成功后直接以 `status=completed`、`result.kind=clarification` 返回，不再调用模型。
若模型同批错误地请求了其他工具，优先执行 ask_user 并跳过其他调用。
ask_user 参数非法时将错误反馈模型修正；跳过的同批调用也有对应 tool result，避免破坏调用协议。
用户补充后使用同 memoryId、新 requestId 创建下一轮，附带历史及最新约束。

## 最终结果

- `answer`：普通回答，只有 kind 和 message；客户端不应用路线。
- `clarification`：只通过 ask_user 返回，最多三个问题。
- `plan`：nodes、legs、days 从编辑草稿取得；message、feasible、issues、warnings、assumptions、estimatedCost、unknownCosts 由模型提供。

完整方案仍需用户确认，`requiresAcceptance=true`。`feasible` 是模型判断，不是后端的约束证明。

## 运行限制与模型要求

默认 `app.agent.max-model-turns=16`、`max-tool-calls=48`、`max-direction-requests=90`、`execution-timeout=240s`。
地图额度在一轮内共用，相同起终点/方式复用查询结果；反向路线单独查询。
取消/超时会阻止后续调用与最终结果提交；已经进入上游的请求是否立即中断取决于客户端。
已结束任务的迟到结果和进度不会覆盖其终态。

模型使用现有 `openAiChatModel`，必须支持原生工具调用。新 Agent 不走旧 Ollama 无工具分支；
若将该模型地址指向其他兼容服务，也必须提供支持工具调用的模型。
旧 `RouteComposerService`、`OllamaRouteComposerService` 仍供旧规划服务使用，`AgentPlanAssembler` 保留兼容计算能力；
这些类都不参与新 Agent 的业务编排。

工具事件对应实际执行；参数错误/查询失败作为结构化结果交回模型，由模型决定换工具、改参数或追问。
整体失败、取消及调用上限继续使用现有任务状态表达。
