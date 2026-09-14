import { defineConfig } from '@playwright/test'

function requiredEnvironment(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing required E2E environment variable: ${name}`)
  return value
}

function resolveSlowMo(): number {
  const environmentValue = process.env.PLAYWRIGHT_SLOW_MO ?? '0'
  const slowMo = Number(environmentValue)

  if (
    !/^\d+$/.test(environmentValue) ||
    !Number.isFinite(slowMo) ||
    !Number.isInteger(slowMo) ||
    slowMo < 0 ||
    slowMo > 10_000
  ) {
    throw new Error('PLAYWRIGHT_SLOW_MO must be an integer between 0 and 10000.')
  }

  return slowMo
}

const resolvedSlowMo = resolveSlowMo()
const postgresPort = requiredEnvironment('E2E_POSTGRES_PORT')
const backendPort = requiredEnvironment('E2E_BACKEND_PORT')
const frontendPort = requiredEnvironment('E2E_FRONTEND_PORT')
const frontendOrigin = `http://localhost:${frontendPort}`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  reporter: [['./e2e/redacting-reporter.ts']],
  outputDir: '.playwright-output',
  preserveOutput: 'never',
  use: {
    baseURL: frontendOrigin,
    browserName: 'chromium',
    launchOptions: {
      slowMo: resolvedSlowMo,
    },
    viewport: { width: 1280, height: 800 },
    screenshot: 'off',
    trace: 'off',
    video: 'off',
  },
  webServer: [
    {
      command: './mvnw spring-boot:run',
      cwd: '../backend',
      url: `http://localhost:${backendPort}/actuator/health`,
      timeout: 120_000,
      reuseExistingServer: false,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        DATABASE_URL:
          `jdbc:postgresql://localhost:${postgresPort}/${requiredEnvironment('E2E_POSTGRES_DB')}`,
        POSTGRES_USER: requiredEnvironment('E2E_POSTGRES_USER'),
        POSTGRES_PASSWORD: requiredEnvironment('E2E_POSTGRES_PASSWORD'),
        SERVER_PORT: backendPort,
        ALLOWED_ORIGIN: frontendOrigin,
        BOOTSTRAP_ADMIN_ENABLED: 'true',
        BOOTSTRAP_ADMIN_EMAIL: requiredEnvironment('E2E_ADMIN_EMAIL'),
        BOOTSTRAP_ADMIN_DISPLAY_NAME: requiredEnvironment('E2E_ADMIN_DISPLAY_NAME'),
        BOOTSTRAP_ADMIN_PASSWORD: requiredEnvironment('E2E_ADMIN_PASSWORD'),
      },
    },
    {
      command: `npm run dev -- --host localhost --port ${frontendPort}`,
      cwd: '.',
      url: frontendOrigin,
      timeout: 60_000,
      reuseExistingServer: false,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        VITE_API_BASE_URL: `http://localhost:${backendPort}`,
      },
    },
  ],
})
