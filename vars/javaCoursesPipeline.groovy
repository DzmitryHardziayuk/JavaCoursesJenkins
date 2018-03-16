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
    final String repositoryUrl = "127.0.0.1:80/java_courses_2018/${config.username}".toLowerCase();

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
            def dockerfile = """
FROM jetty:9.4-alpine
ADD http://central.maven.org/maven2/org/eclipse/jetty/jetty-runner/9.4.8.v20171121/jetty-runner-9.4.8.v20171121.jar /usr/local/jettyapps/
COPY ./deploy_modules/* /usr/local/jettyapps/"""

            for (def module : config.modules) {
                def file = findFiles(glob: "${module.name}/target/*.*ar")[0]
                echo "Copying file: $file"
                exposePorts.add(module.port)
                dockerfile+="\nEXPOSE ${module.port}"
                startScript.append("nohup java -jar /usr/local/jettyapps/jetty-runner-9.4.8.v20171121.jar --port ${module.port} /usr/local/jettyapps/${file.name} > springlog.log 2>&1 &\n")
                sh "cp $file deploy_modules/"
            }
            dockerfile+="""
COPY jettystart.sh /usr/local/bin/
USER root
RUN chmod 755 /usr/local/jettyapps/jetty-runner-9.4.8.v20171121.jar
USER jetty
ENTRYPOINT ["jettystart.sh"]"""

            startScript.append("echo \"Servers started\"\n")
            startScript.append("tail -f springlog.log")
            echo "${startScript.toString()}"
            echo "${dockerfile}"

            writeFile file: "Dockerfile", text: dockerfile
            writeFile file: "jettystart.sh", text: startScript.toString()
            sh "chmod +x jettystart.sh"
            sh "chmod 755 deploy_modules/*"

            withDockerServer([uri: "unix:///var/run/docker.sock"]) {
                withDockerRegistry([url: "http://127.0.0.1:80/"]) {

                    def buildarg = ""
                    for (int port : exposePorts)
                        buildarg += "--build-arg ports=$port "
                    def image = docker.build(repositoryUrl, "$buildarg.")
                    image.push()
                }
            }
        }

        stage("Start container") {
            final String containerName = config.username.toLowerCase()
            sh "docker stop $containerName || exit 0"
            sh "docker rm $containerName || exit 0"
            sh "docker run -dP --name $containerName $repositoryUrl"
            sh "docker ps | grep java\\_courses\\_2018 > /var/lib/jenkins/userContent/dockerPs.txt"
            final String currentHost = sh(
                    script: "cat /etc/hosts | head -2 | tail -1 | awk '{print \$2}'",
                    returnStdout: true
            ).trim()
            println "http://$currentHost:8080/userContent/dockerPs.txt"
        }
    }
}