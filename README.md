# jenkins-atom-git-checkout

`checkout scm` 的原子版本：不需要任务里预先配置 SCM。

原子自己 clone 或更新仓库，把 `GIT_COMMIT` / `GIT_BRANCH` / `GIT_URL` 作为出参交给后续步骤。凭据以字符串传入，只写进 0600 的 credential 文件交给 git 读取，不进命令行、不进日志。

```bash
./build.sh && ./tools/smoke.sh
```
