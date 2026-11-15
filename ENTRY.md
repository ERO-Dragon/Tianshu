# Tianshu 入口仓库

`master` 是入口分支，不包含源码；源码在固定工作区的分支里。

## 首次初始化

```powershell
.\setup-workspace.cmd
```

会创建：

```text
..\Tianshu-ferry\      ferry 分支
..\Tianshu-features\   features 分支
..\Tianshu-adapter\    当前 adapter 分支（默认 1.21.1）
```

并把本机 `libs/*.jar` 复制到 adapter 工作区。

## 启动

```powershell
# adapter 启动：全部功能模组
.\run.cmd -Version 1.21.1
.\run.cmd -Version 26.3 -Loader neoforge

# 纯舟楫：不安装功能模组
.\run.cmd -Version 1.21.1 -Ferry

# 从功能模组目录启动：自身 + 硬依赖
cd ..\Tianshu-features\tianshu-echo
..\run-feature.cmd

# 从 ferry 目录启动：纯舟楫
cd ..\Tianshu-ferry
.\run-ferry.cmd
```

- `run.ps1` 会先初始化工作区，再切 `Tianshu-adapter` 到指定版本；
- 加载器由 `-Loader` 决定，默认 NeoForge；
- 功能模组入口委托给 adapter，加载器来自当前 adapter。

## 共享层

- 改 `ferry`：`cd ..\Tianshu-ferry`
- 改 `features`：`cd ..\Tianshu-features`
- 两个目录已固定在对应分支，不需要在主仓库 `git checkout`。

## 文档

`doc/` 只在入口仓维护，`ferry` / `features` / `adapter` 分支不再保留副本。

测试通过 `WorkspacePaths.doc(...)` 解析文档根，顺序是：

1. `-Dtianshu.docRoot=<目录>`；
2. 当前工作区自己的 `doc/`；
3. 顺着 `.git` 文件找到主工作区（入口仓）的 `doc/`。

所以从 adapter 工作区跑测试时，文档会直接落到入口仓，不需要额外配置。

各模块 README 用 `../../Tianshu/doc/...` 指过来，入口仓目录改名时要一并替换。

## 分支

- 版本分支：`adapter/1.20` … `adapter/26.3`，只保留 adapter/common；
- `ferry`：平台层；
- `features`：功能模组；
- `master`：入口脚本，不含源码；
- `archive/*`：历史归档标签。