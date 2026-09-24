# physai-isco-2413 — 財務アナリスト（ISCO 2413）の事務を担うロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-2413`、ISCO 2413 財務アナリスト）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README はこの職種を純粋な認知労働（robotics gate なし）とするが、blueprint.edn は `:itonami.blueprint/robotics true` を宣言している。ここではこの職種自体に伴う物理的な取り扱いを**仮定して**測る: レポート用プリンタへの用紙補給と、印刷した分析資料の会議室への配送。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:ream-into-printer-tray` | manipulator | A4 用紙の束を補給箱からプリンタの大容量トレイへ持ち上げる（2 リンクアーム） | 肩関節ピークトルク | 60 N·m（estimate） |
| `:analysis-pack-to-boardroom` | transport | 印刷した四半期分析資料を印刷室から会議室へ運ぶ（AMR、20 kg 積載） | 1 区間の所要時間 | 60 s（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:test`（`test/finanalyst/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。
この repo 自身の `.kotoba` test は kbb では走らない（fleet の JVM gate が走らせる）。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **アーム**: 肩トルクは 1 束（2.5 kg）で 30.91 N·m、2 束で 45.55 N·m、3 束（7.5 kg）で 60.42 N·m で限界を超える。
   限界 60 N·m に達する積荷は **7.43 kg**（約 3 束）。5 束入りの箱ごとの補給は無理で、2 束ずつ運ぶことになる。
2. **搬送**: 所要時間は 15 m で 16.72 s、50 m で 51.72 s、90 m で 91.72 s。速度上限 1.0 m/s が効き、限界 60 s を超える距離は **58.29 m**。
3. **premise 自体が仮定**: README に Robotics premise が書かれていない。premise が書かれたらそれに合わせて case を置き換える（成長の第一候補）。
4. **estimate のままの値**: 肩トルク上限 60 N·m（協働ロボットの仕様書）、配送の所要時間 60 s（会議運営の実測）、用紙 1 束 2.5 kg（用紙メーカーの仕様で確かめる）、
   アームの寸法・質量、AMR の駆動力・転がり抵抗係数。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-2413 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-2413 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
