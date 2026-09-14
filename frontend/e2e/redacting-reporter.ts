import type {
  FullConfig,
  FullResult,
  Reporter,
  Suite,
  TestCase,
  TestError,
  TestResult,
} from '@playwright/test/reporter'

const REDACTED = '[redacted]'

function redact(value: string): string {
  let safe = value
  for (const secret of [
    process.env.E2E_ADMIN_PASSWORD,
    process.env.E2E_OWNER_PASSWORD,
    process.env.E2E_POSTGRES_PASSWORD,
  ]) {
    if (secret) safe = safe.replaceAll(secret, REDACTED)
  }
  return safe
    .replace(/([?&]token=)[^&\s"'<>]+/giu, `$1${REDACTED}`)
    .replace(/((?:["']?token["']?)\s*[:=]\s*["'])[^"']+/giu, `$1${REDACTED}`)
    .replace(/(<input\b[^>]*\bname=["']token["'][^>]*\bvalue=["'])[^"']+/giu,
      `$1${REDACTED}`)
    .replace(/(SPOTYOURSESSION|XSRF-TOKEN)=([^;\s]+)/giu, `$1=${REDACTED}`)
}

function errorMessage(error: TestError): string {
  return redact(error.message ?? error.value ?? 'Unknown Playwright error')
}

export default class RedactingReporter implements Reporter {
  onBegin(_config: FullConfig, suite: Suite): void {
    process.stdout.write(`Running ${suite.allTests().length} browser E2E test(s)\n`)
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    const marker = result.status === 'passed' ? 'PASS' : result.status.toUpperCase()
    process.stdout.write(`${marker} ${test.title}\n`)
    for (const error of result.errors) {
      process.stderr.write(`${errorMessage(error)}\n`)
    }
  }

  onError(error: TestError): void {
    process.stderr.write(`${errorMessage(error)}\n`)
  }

  onEnd(result: FullResult): void {
    process.stdout.write(`Browser E2E result: ${result.status}\n`)
  }
}
