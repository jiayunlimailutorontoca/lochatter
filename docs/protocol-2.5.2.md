# 2.5.2

2.5.2 仅修订 Android 客户端。不新增帧，不新增 `kind`，数据库 schema 仍为 9。服务端 `Version` 仍为 `2.3.0`。

Android `versionCode` 为 52，`versionName` 为 `2.5.2`。

本机整理增加一档可选权重：Qwen3.5 2B 的 GGUF Q4_0，约 1.2 GB。文件名为 `Qwen3.5-2B-Q4_0.gguf`，从魔搭 `unsloth/Qwen3.5-2B-GGUF` 下载。运行库为 Qualcomm GenieX 0.7.0 的 llama.cpp 插件，Hexagon 后端为 ggml-hexagon。安装包内带有 Hexagon v73、v75、v79、v81，对应骁龙 8 Gen 2（SM8550）、8s Gen 3（SM8635）、7+ Gen 3（SM7675）、8 Gen 3（SM8650）、8 Elite（SM8750）与 8 Elite Gen 5（SM8850）。这些机型须以 `npu` 创建。若创建抛错，则改用同一文件的 `cpu`，并在设置的整理模型一行写明，不得仍显示为 NPU。

其余机型不得请求 `npu`。华为麒麟与联发科天玑若选中该档，须走 `cpu`。这两类芯片的民间实现需要按芯片编译，没有可随本安装包发布的预编译库。

应用 `minSdk` 仍为 26。GenieX 声明的最低版本是 27。Android 8.0 上不得加载该库，选中该档后须提示需要 Android 8.1。

原有三档 LiteRT-LM 权重仍走 CPU。默认仍为 Qwen3.5 4B。下载仍只发生在设置里的「下载这个模型」。权重不得打进安装包。对话文本不得离开手机。生成仍逐字显示，停止仍保留已写出的文字。
