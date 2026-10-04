[CmdletBinding()]
param(
    [string]$ApiBaseUrl = 'http://localhost:8080',
    [string]$Prompt = 'Create a workflow that downloads customer orders, validates them, generates invoices in parallel, uploads them and sends a notification.',
    [Guid]$ProposalId = [Guid]::Empty,
    [switch]$Approve,
    [switch]$Execute,
    [ValidateRange(10, 1800)][int]$GenerationTimeoutSeconds = 150,
    [ValidateRange(5, 600)][int]$ExecutionTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
if ($Execute -and -not $Approve) {
    throw '-Execute requires -Approve. Generation, approval, and execution are separate actions.'
}
$baseUrl = $ApiBaseUrl.TrimEnd('/')
if ($PSBoundParameters.ContainsKey('ProposalId') -and $ProposalId -eq [Guid]::Empty) {
    throw '-ProposalId must be a nonempty proposal UUID.'
}
if ($ProposalId -ne [Guid]::Empty) {
    if ($PSBoundParameters.ContainsKey('Prompt')) {
        throw '-Prompt cannot be supplied with -ProposalId; the stored preview is authoritative.'
    }
    $proposal = Invoke-RestMethod -Uri "$baseUrl/api/v1/ai/workflows/proposals/$ProposalId" -TimeoutSec 30
} else {
    $requestBody = @{ prompt = $Prompt } | ConvertTo-Json
    $proposal = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/ai/workflows/generate" `
        -ContentType 'application/json' -Body $requestBody -TimeoutSec $GenerationTimeoutSeconds
}
Write-Host 'Workflow preview (task names describe simulated NOOP capabilities):'
$proposal | ConvertTo-Json -Depth 30 | Write-Output
if (-not $proposal.proposalId) { throw 'Generation returned no proposal identifier.' }
if (-not $proposal.validation.valid) { throw 'Conductor rejected the DAG. Nothing was approved or executed.' }
if (-not $Approve) {
    Write-Host "Preview only. Reuse -ProposalId '$($proposal.proposalId)' -Approve to persist this exact proposal."
    return
}

$workflow = Invoke-RestMethod -Method Post `
    -Uri "$baseUrl/api/v1/ai/workflows/proposals/$($proposal.proposalId)/approve" -TimeoutSec 30
if (-not $workflow.id) { throw 'Approval returned no normal workflow identifier.' }
$storedWorkflow = Invoke-RestMethod -Uri "$baseUrl/api/v1/workflows/$($workflow.id)" -TimeoutSec 30
Write-Host "Approved and retrieved normal workflow $($storedWorkflow.id)."
if (-not $Execute) {
    Write-Host 'Workflow persisted. No execution was requested.'
    return
}

$jobBody = @{ name = 'ai-workflow-smoke' } | ConvertTo-Json
$job = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/workflows/$($workflow.id)/jobs" `
    -ContentType 'application/json' -Body $jobBody -TimeoutSec 30
if (-not $job.id) { throw 'Workflow submission returned no job identifier.' }
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($ExecutionTimeoutSeconds)
do {
    $job = Invoke-RestMethod -Uri "$baseUrl/api/v1/jobs/$($job.id)" -TimeoutSec 10
    Write-Host "Job $($job.id): $($job.status)"
    if ($job.status -eq 'COMPLETE') {
        Write-Host "Smoke test completed through the normal engine with $(@($job.tasks).Count) tasks."
        return
    }
    if ($job.status -in @('FAILED', 'CANCELLED')) { throw "Job ended with status $($job.status)." }
    if ([DateTimeOffset]::UtcNow -ge $deadline) { throw "Execution polling timed out for job $($job.id)." }
    Start-Sleep -Seconds 2
} while ($true)
