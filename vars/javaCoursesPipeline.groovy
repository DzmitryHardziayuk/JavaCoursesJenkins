def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    if (config.modules == null) {
        config.modules = [[]]
    }

    if (!config.username) {
        config.username = "undefined_user"
    }

    node() {
        stage("Checkout") {
            checkout(scm)
            env.JAVA_HOME = "${tool 'java8'}"
            env.M2_HOME = "${tool 'M3'}"
            env.PATH = "${env.PATH}:${env.JAVA_HOME}/bin:${env.M2_HOME}/bin"
            env.COMMIT_MESSAGE = sh(script: "git log -1 --pretty=%B", returnStdout: true).trim()
            sh "printenv"
        }

        stage("Install") {
            try {
                sh "mvn clean install -DskipTests"
            } finally {
                try {
                    junit '**/target/surefire-reports/*.xml'
                } catch (junitError) {
                }
            }
        }

        stage("Docker") {
            def exposePorts = []// provided ports will be added to Dockerfile, then all  ports could be exposed by '-P' flag in docker run
            StringBuilder startScript = new StringBuilder("#!/bin/sh\n")
            sh "mkdir -p deploy_modules"
            sh "rm -f deploy_modules/*"
            for (def module : config.modules) {
                def file = findFiles(glob: "${module.name}/target/*.*ar")[0]
                echo "Copying file: $file"
                exposePorts.add(module.port)
                startScript.append("nohup java -jar /usr/local/jettyapps/jetty-runner-9.4.8.v20171121.jar --port ${module.port} /usr/local/jettyapps/${file.name} > springlog.log 2>&1 &\n")
                sh "cp $file deploy_modules/"
            }
            startScript.append("echo \"Servers started\"\n")
            startScript.append("tail -f springlog.log")
            echo "${startScript.toString()}"
            def dockerfile = libraryResource 'Dockerfile'
            writeFile file: "Dockerfile", text: dockerfile
            writeFile file: "jettystart.sh", text: startScript.toString()
            sh "chmod +x jettystart.sh"
            sh "chmod 755 deploy_modules/*"

            withDockerServer([uri: "unix:///var/run/docker.sock"]) {
                withDockerRegistry([url: "http://127.0.0.1:80/"]) {

                    def buildarg = ""
                    for (int port : exposePorts)
                        buildarg += "--build-arg ports=$port "

                    def image = docker.build("127.0.0.1:80/java_courses_2018/${config.username}", "$buildarg.")
                    image.push()
                }
            }
        }
    }
}