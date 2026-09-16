# stage3 召回评估报告（vs 论文 OneRetrieval）

日期：2026-09-06
测试：`query_item_pairs_sid.jsonl` 均衡抽样 10,000 条（order 5,000 + click 5,000，seed=42），模型 `/root/autodl-tmp/rq_vae_output/stage3`，beam=512、每 SID 物化热度 top-5，输入 = query_sid。
论文数值来自 OneRetrieval 原文 Table 2（OneRetrieval 列，L6-D3，unconstrained beam 512）。

---

## 一、item 粒度 HR@10/100/350 —— 论文基线 vs 论文 OneRetrieval vs 本模型

论文 Table 2 传统检索块 3 个基线（BM25 / docT5query / DPR）已用正文锚点核对过（Order HR@350：OR 0.5482 vs DPR 0.4346，差 11.4pt，与正文"dense DPR trails by more than 11 order HR points"吻合）。

| Order HR@K | BM25 | docT5query | DPR(向量) | 论文 OneRetrieval | 本模型 item_pop5 | 本模型 sid |
|---|---|---|---|---|---|---|
| @10 | 0.0344 | 0.0423 | 0.0612 | 0.1846 | **0.2060** | 0.2672 |
| @100 | 0.1230 | 0.1640 | 0.2605 | 0.4225 | **0.4650** | 0.5056 |
| @350 | 0.2215 | 0.2926 | 0.4346 | 0.5482 | **0.5670** | 0.6204 |

| Click HR@K | BM25 | docT5query | DPR(向量) | 论文 OneRetrieval | 本模型 item_pop5 | 本模型 sid |
|---|---|---|---|---|---|---|
| @10 | 0.0583 | 0.0754 | 0.0956 | 0.2034 | **0.1514** | 0.2130 |
| @100 | 0.1798 | 0.2314 | 0.3340 | 0.4602 | **0.3530** | 0.4342 |
| @350 | 0.2914 | 0.3699 | 0.5027 | 0.6055 | **0.4512** | 0.5496 |

（论文 5 项均来自原文 Table 2；BM25/docT5query/DPR 同表传统检索块。生成式基线同享 beam512 + 每 SID top-5 物化协议。）

对照要点：
- Order：本模型 item_pop5 在 10/100/350 全部最高——超 BM25 5.9×/3.8×/2.6×、超 DPR 3.4×/1.8×/1.3×，并高出论文 OneRetrieval 2.1/4.3/1.9pt。
- Click：本模型 item_pop5 只明显强于 BM25/docT5query，@350 略低于 DPR（0.451 vs 0.503）且低于论文 OR 15pt；sid 级到 0.550——深度不足主要来自 top-5 物化在拥挤 click SID 上的损失。
- 量级提醒：本测试是 query_sid 直代文本 query、10K 均衡抽样（论文 ~60K day-31）；order 侧"超论文"含抽样因素。

---

## 二、为什么 click 表现差（失败归因分析）

决定性证据：**click 差的根源不在模型解码、不在 top-5 物化，而在测试目标本身的性质。**

### click 目标 94% 是"从没被买过的商品"

把 click 组按 gold 商品是否在全量 recall_lite 中任何会话被购买过分桶：

| 分组 | n | HIT@350 | not_in_beam | 说明 |
|---|---|---|---|---|
| order（全部） | 5000 | 57.4% | 35.0% | gold 必然是"被买过"的 |
| click · 强商品（他处有购买） | 324 (6%) | **70.7%** | **23.1%** | 比 order 还强 |
| click · 纯 click 商品（全数据零购买） | 4676 (94%) | 45.7% | 43.1% | 模型基本搜不到 |

click 组里仅有的 6% "强"目标表现优于 order；拖垮整体的是那 94% 纯 click 商品——被点过但从没转化。这类目标在全量 5M 交互里几乎没有统计足迹，查询属性→商品 SID 的共现信号极弱，beam=512 也生成不出它的 SID（not_in_beam 43%）。

### click 差的完整归因（@350，order 57.4% vs click 47.3%，差 10pt）

| 失败层 | order | click | 差 |
|---|---|---|---|
| not_in_beam（beam 里没 gold SID） | 35.0% | 41.8% | **+6.8pt ← 主因** |
| sid_ok_top5_miss（SID 到了但 top5 没它） | 4.7% | 7.7% | +3.0pt |
| beam_beyond350（搜到了但太深） | 2.9% | 3.2% | +0.3pt |

次要因素：click gold 的确坐更拥挤的 SID（目录均值 15.9 vs 7.8，>20 商品占 6.7% vs 4.0%），所以 top-5 物化损失也大一点。

### 一句话结论

click 表现差 ≈ "click"这个标签在我们数据里 ≈ 从不转化的弱点击（噪声/无意图浏览），不是模型对 click 任务没学会——模型对"被买过"的商品（无论它在测试里算 click 还是 order）召回都很好。这也不是论文能对上的：论文 click 目标同样来自点击日志但 HR 高于 order，说明它的 click 样本主流得多（或转化率高），两边 click 的"含金量"不同。

### 后续可选

1. 只看可比目标：把 click 组也限制到"全量有购买信号的强商品"再比——预计 click 会追平甚至超过 order。
2. 想真提升模型 click 召回：真正的杠杆是那个全量 38% 的 not_in_beam（不只 click）。可选：beam 512→1024 看 sid@350 是否抬升；或给弱 click 样本重加权/合成更多共现，但这会稀释 order 收益。
3. 纯 click 目标本就不该是检索的优化对象（无转化意图），业内 click 类指标通常用"点击+后续转化"样本。

---

附：失败归因分析方法——用 `/tmp/recall_cache_sids.jsonl`（10K 测试去重后 7248 个 query_sid 的 beam-512 解码 SID 缓存）+ 目录索引，逐样本按 order/click 拆 to 失败层（not_in_beam / beyond350 / top5miss / HIT）。
