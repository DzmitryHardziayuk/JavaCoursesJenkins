docker ps | grep java\\_courses\\_2018 | awk '{print $1}' | xargs docker stop
docker system prune -f
docker images | grep java\\_courses\\_2018 | awk '{print $3}' | xargs docker rmi -f