# 2.5.3

2.5.3 仅修订 Android 客户端。不新增帧，不新增 `kind`，数据库 schema 仍为 9。服务端 `Version` 仍为 `2.3.0`。

Android `versionCode` 为 53，`versionName` 为 `2.5.3`。

本机整理不得再走 CPU。三档 LiteRT-LM 权重（Qwen3.5 4B、Qwen3.5 2B、Qwen3 1.7B）须走 GPU。默认仍为 Qwen3.5 4B。4B 仍要求手机内存达到 8 GB。

Qwen3.5 2B 的 GGUF Q4_0 仍从魔搭 `unsloth/Qwen3.5-2B-GGUF` 下载，文件名为 `Qwen3.5-2B-Q4_0.gguf`。运行库仍为 Qualcomm GenieX 0.7.0。骁龙 8 Gen 2（SM8550）、8s Gen 3（SM8635）、7+ Gen 3（SM7675）、8 Gen 3（SM8650）、8 Elite（SM8750）与 8 Elite Gen 5（SM8850）须以 `npu` 创建。创建抛错，或芯片不在这份列表里，须改用同一文件的 `gpu`，并在设置的整理模型一行写明。不得改用 `cpu`。

GPU 打不开时须把错误告诉用户，不得静默改走 CPU。下载仍只发生在设置里的「下载这个模型」。权重不得打进安装包。对话文本不得离开手机。生成仍逐字显示，停止仍保留已写出的文字。
