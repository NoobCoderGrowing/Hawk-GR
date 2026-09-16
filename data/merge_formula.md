# 合并判定公式（OneRetrieval/KAE 码本构造）

> 对应 `claude/KAE.pdf` §3.3 的贪心合并，加上论文的 4 个正则器。
> 本文件记录当前用于合并的公式、各分量定义、贪婪循环与参数。

## 1. 合并判定公式（对每个候选组对 g_a, g_b）

```
cost(g_a, g_b) = IL(g_a, g_b)                    ← 信息损失
              + α·|H(g_a) − H(g_b)|              ← 熵同质性
              + β·|p(g_a) − p(g_b)|              ← 激活率同质性
              + γ·1[family(g_a) ≠ family(g_b)]   ← 语义一致性 (跨族=1)
              + δ·1[|g_a| + |g_b| > 6]           ← 组大小软上限 (超6=1)
```

LaTeX 版（供渲染环境）：

```latex
\operatorname{cost}(g_a,g_b) =
\underbrace{\operatorname{IL}(g_a,g_b)}_{\text{信息损失}}
+ \alpha\,\underbrace{|H(g_a)-H(g_b)|}_{\text{熵同质性}}
+ \beta\,\underbrace{|p(g_a)-p(g_b)|}_{\text{激活率同质性}}
+ \gamma\,\underbrace{\mathbb{1}[\operatorname{family}(g_a)\ne\operatorname{family}(g_b)]}_{\text{语义一致性}}
+ \delta\,\underbrace{\mathbb{1}[|g_a|+|g_b|>6]}_{\text{组大小软上限}}
```

## 2. 各分量定义

| 项 | 定义 | 备注 |
|---|---|---|
| `IL(g_a, g_b)` | **组级** IL：`½(H(g_a)+H(g_b)) − MI(g_a,g_b)`，把每个组当作一个二元变量（商品是否含该组任一类） | 实际实现 |
| `IL(X, Y)` | `½(H(X)+H(Y)) − MI(X,Y)` | 对称条件熵，即 variation of information 的一半 |
| `H(X)` | 二元熵 `H(p_X) = −p·log₂p − (1−p)·log₂(1−p)` | 类别 X 视为二元随机变量（商品是否含该类） |
| `p(g)` | **并集**激活率 `P(商品含 g 中任一类)` | 不是求和——两类可在同一商品上同现（如“金属工艺”同时含 工艺 + 材质_金属材质） |
| `H(g)` | `H(p(g))` | 并集率的二元熵 |
| `sem` | `1[family(g_a) ≠ family(g_b)]` | 11 个语义家族 |
| `cap` | `1[|g_a| + |g_b| > 6]` | 软上限：只惩罚、不停合并 |

> **与论文 §3.3 的差异**：论文把 `IL(g_a,g_b)` 定义为类别对平均距离
> `(1/(|g_a|·|g_b|))·Σ_{X∈g_a} Σ_{Y∈g_b} IL(X,Y)`；本项目实际实现用
> **组级** IL `½(H(g_a)+H(g_b))−MI(g_a,g_b)`（组内含多类别时二者数值不同）。

## 3. 贪婪循环

1. 全部可合并类别（39 个）各自成组；锚点组 **产品_核心产品 单独 hold out，永不参与合并**。
2. 对每个组对计算 `cost(g_a, g_b)`，选 cost 最小的一对合并。
3. 合并后新组的 `p(g)`、`H(g)` 用并集率重算；组间 `IL` 按组级定义 `½(H(g_a)+H(g_b))−MI(g_a,g_b)` 重算。
4. 重复直到并成 1 组（共 38 步）。

## 4. 参数

| 参数 | 值 | 含义 |
|---|---|---|
| α | 1 | 熵同质性权重 |
| β | 1 | 激活率同质性权重 |
| γ | 1 | 语义一致性权重 |
| δ | 1 | 组大小软上限权重 |
| cap 阈值 | 6 | 合并后类别数 > 6 时 cap=1 |

## 5. 相关文件

- `claude/KAE.pdf` — OneRetrieval 论文（§3.2 码本、§3.3 合并与停止准则、§3.4.4 正则器）
