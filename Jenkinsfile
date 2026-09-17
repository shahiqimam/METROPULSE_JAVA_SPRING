// MetroPulse CI.
//
// The order is deliberate: everything cheap and fast runs before anything slow, so a compile error
// or a broken unit test fails in seconds rather than after a container has been pulled. The
// integration tests need real PostgreSQL/PostGIS, and the docker stage needs a daemon, so both are
// guarded rather than assumed.

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
    METROPULSE_TEST_DB_URL = 'jdbc:postgresql://localhost:5433/metropulse_ci'
    METROPULSE_TEST_DB_USERNAME = 'metropulse'
    METROPULSE_TEST_DB_PASSWORD = 'metropulse'
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
            -p 5433:5432 \
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
            if curl -sf http://localhost:8080/api/v1/health >/dev/null; then break; fi
            sleep 3
          done
          infra/scripts/smoke-test.sh http://localhost:8080 "$(grep METROPULSE_INGEST_KEY .env.prod | cut -d= -f2)"
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
