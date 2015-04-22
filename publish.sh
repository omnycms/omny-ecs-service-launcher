#!/bin/sh
pip install awscli
aws s3 cp output/ecs-service-launcher-$1.zip s3://omny-ecs-service-launcher-releases/ecs-service-launcher-$1.zip
