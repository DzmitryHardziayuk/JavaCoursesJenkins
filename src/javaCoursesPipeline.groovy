def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    if (config.modules == null) {
        config.modules = []
    }

    node() {
            stage("Checkout") {
                    checkout(scm)
                env.JAVA_HOME = "${tool '8u152'}"
                env.M2_HOME = "${tool 'M3'}"
                env.PATH = "${env.PATH}:${env.JAVA_HOME}/bin:${env.M2_HOME}/bin"
                env.COMMIT_MESSAGE = sh(script: "git log -1 --pretty=%B", returnStdout: true).trim()
                sh "printenv"
            }

            stage("Install") {
                try {
                    sh "mvn clean install"
                } finally {
                    try {
                        junit '**/target/surefire-reports/*.xml'
                    } catch (junitError) {
                    }
                }
            }

            stage("Docker") {

            }
    }
}