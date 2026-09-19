// MetroPulse CI.
//
// The order is deliberate: everything cheap and fast runs before anything slow, so a compile error
// or a broken unit test fails in seconds rather than after a container has been pulled. The
// integration tests need real PostgreSQL/PostGIS, and the docker stage needs a daemon, so both are
// guarded rather than assumed.
//
// The agent must have a POSIX shell and a Docker daemon: every step here is `sh`, and four stages
// build or run containers. A Windows built-in node cannot run it - Jenkins' `sh` step needs a Unix
// shell - so on a Windows controller this wants a Linux agent, or a Jenkins that is itself in a
// Linux container with the Docker socket mounted. Carrying a second shell dialect through the file
// would be worse than requiring the one the project already targets.
//
// Ports are variables rather than constants because the obvious defaults collide. 8080 is Jenkins'
// own default port, so a pipeline that publishes the demo stack there fails to bind on exactly the
// machine most likely to run it, and 5433 is what a developer's dev stack already holds.

pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 45, unit: 'MINUTES')
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }

  environment {
    // The image tag is the commit, not a moving name: "which build is running" should have exactly
    // one answer, and 'latest' never does.
    IMAGE_TAG = "${env.GIT_COMMIT ? env.GIT_COMMIT.take(12) : 'local'}"

    // Test credentials. Nothing here is a secret; the values only have to match each other.
    POSTGRES_DB = 'metropulse_ci'
    POSTGRES_USER = 'metropulse'
    POSTGRES_PASSWORD = 'metropulse'

    // Deliberately not 5433: that is the dev stack's port, and a build should not fight a developer
    // for it on a machine that runs both.
    CI_POSTGRES_PORT = "${env.CI_POSTGRES_PORT ?: '5434'}"
    METROPULSE_TEST_DB_URL = "jdbc:postgresql://localhost:${env.CI_POSTGRES_PORT ?: '5434'}/metropulse_ci"
    METROPULSE_TEST_DB_USERNAME = 'metropulse'
    METROPULSE_TEST_DB_PASSWORD = 'metropulse'

    // Deliberately not 8080: Jenkins listens there by default, so the demo stack would fail to bind
    // against the very server running this build.
    METROPULSE_HTTP_PORT = "${env.METROPULSE_HTTP_PORT ?: '8090'}"
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        sh 'git --no-pager log -1 --oneline'
      }
    }

    stage('Compile') {
      steps {
        // Fails in seconds if anything does not build, before a container is pulled.
        sh './mvnw -B -ntp clean compile'
      }
    }

    stage('Unit tests') {
      steps {
        // Pure logic only: thresholds, rules, geometry, the workflow table. No database, no broker.
        sh '''
          ./mvnw -B -ntp test \
            -Dtest='*Test' \
            -Dsurefire.failIfNoSpecifiedTests=false \
            -DexcludedGroups=integration \
            -Dtest='!*IntegrationTest'
        '''
      }
      post {
        always {
          junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
        }
      }
    }

    stage('Start PostGIS') {
      steps {
        // A real PostGIS, because the projection, the geometry and the partial indexes cannot be
        // tested against anything else. H2 would prove nothing.
        sh '''
          docker rm -f metropulse-ci-postgres >/dev/null 2>&1 || true
          docker run -d --name metropulse-ci-postgres \
            -e POSTGRES_DB=${POSTGRES_DB} \
            -e POSTGRES_USER=${POSTGRES_USER} \
            -e POSTGRES_PASSWORD=${POSTGRES_PASSWORD} \
            -p ${CI_POSTGRES_PORT}:5432 \
            postgis/postgis:16-3.4

          for i in $(seq 1 60); do
            if docker exec metropulse-ci-postgres pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB} >/dev/null 2>&1; then
              echo "PostGIS is ready"
              exit 0
            fi
            sleep 2
          done
          echo "PostGIS did not become ready"
          exit 1
        '''
      }
    }

    stage('Integration tests') {
      steps {
        // The full suite: migrations, PostGIS behaviour, the Kafka consumer against an in-process
        // broker, the charger concurrency race, authentication and the WebSocket.
        sh './mvnw -B -ntp verify'
      }
      post {
        always {
          junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
          sh 'docker rm -f metropulse-ci-postgres >/dev/null 2>&1 || true'
        }
      }
    }

    stage('Frontend') {
      steps {
        dir('frontend') {
          sh 'npm ci'
          // Headless Chrome, so the agent needs a Chrome or Chromium binary available.
          sh 'npm run test'
          sh 'npm run build'
        }
      }
    }

    stage('Docker images') {
      steps {
        sh '''
          docker build -f backend/Dockerfile   -t metropulse/backend:${IMAGE_TAG}   .
          docker build -f simulator/Dockerfile -t metropulse/simulator:${IMAGE_TAG} .
          docker build -f frontend/Dockerfile  -t metropulse/frontend:${IMAGE_TAG}  .
          docker build -f infra/nginx/Dockerfile -t metropulse/nginx:${IMAGE_TAG}   .
        '''
      }
    }

    stage('Deploy demo') {
      steps {
        // Brings up the production-style stack, where only nginx has a published port.
        sh '''
          cp -n .env.prod.example .env.prod || true

          # The port the stack publishes has to match the one the smoke test asks for, and neither
          # can be the one Jenkins is on.
          if grep -q '^METROPULSE_HTTP_PORT=' .env.prod; then
            sed -i "s|^METROPULSE_HTTP_PORT=.*|METROPULSE_HTTP_PORT=${METROPULSE_HTTP_PORT}|" .env.prod
          else
            echo "METROPULSE_HTTP_PORT=${METROPULSE_HTTP_PORT}" >> .env.prod
          fi
          sed -i "s|^METROPULSE_ALLOWED_ORIGINS=.*|METROPULSE_ALLOWED_ORIGINS=http://localhost:${METROPULSE_HTTP_PORT}|" .env.prod

          docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
        '''
      }
    }

    stage('Smoke test') {
      steps {
        // Proves the deployed stack is wired up: edge, auth, reads, a telemetry round trip through
        // the outbox and consumer, and that a viewer is refused a write.
        sh '''
          for i in $(seq 1 60); do
            if curl -sf http://localhost:${METROPULSE_HTTP_PORT}/api/v1/health >/dev/null; then break; fi
            sleep 3
          done
          INGEST_KEY="$(grep METROPULSE_INGEST_KEY .env.prod | cut -d= -f2)"
          infra/scripts/smoke-test.sh "http://localhost:${METROPULSE_HTTP_PORT}" "$INGEST_KEY"
        '''
      }
    }
  }

  post {
    always {
      // Leaving a database container behind would make the next build fail on a port clash.
      sh 'docker rm -f metropulse-ci-postgres >/dev/null 2>&1 || true'
    }
    failure {
      sh 'docker compose -f docker-compose.prod.yml logs --tail 200 || true'
    }
  }
}
