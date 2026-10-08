$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$git = (Get-Command git).Source
function Run-Git([string[]] $arguments) {
    & $git -C $repo @arguments
    if ($LASTEXITCODE -ne 0) { throw ('Git 命令失败：' + ($arguments -join ' ')) }
}
$dirty = & $git -C $repo status --porcelain
if ($LASTEXITCODE -ne 0 -or $dirty) { throw '工作区有未提交修改，先提交自己的改动；未自动 stash/reset/覆盖。' }
$branch = & $git -C $repo branch --show-current
if (!$branch) { throw '当前是 detached HEAD，未同步。' }
$upstream = & $git -C $repo remote get-url upstream
if ($LASTEXITCODE -ne 0 -or $upstream -notmatch 'github\.com[:/]loks666/get_jobs(\.git)?$') {
    throw 'upstream 不是已确认的原项目，请先检查 remote。'
}
Run-Git @('fetch', 'upstream', 'main')
& $git -C $repo merge-base --is-ancestor upstream/main HEAD
if ($LASTEXITCODE -eq 0) { Write-Output '已包含上游最新提交，无需合并。'; exit 0 }
& $git -C $repo merge-tree --write-tree HEAD upstream/main | Out-Null
if ($LASTEXITCODE -ne 0) { throw '上游与本地改动有冲突，未修改工作区；请人工审查后合并。' }
Run-Git @('merge', '--no-edit', 'upstream/main')
Write-Output '已合并上游，保留本地功能。请运行自检、构建，再自行 push；本脚本不推送、不部署或重启。'
