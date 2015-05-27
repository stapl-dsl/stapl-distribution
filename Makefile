dnetcloud:
	mvn clean package
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-client-1:/home/ubuntu/stapl
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-client-2:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-coordinator-c2:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-coordinator-c4:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-coordinator-c8:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-1:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-2:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-3:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-4:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-5:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-6:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-7:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-8:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-9:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-10:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-11:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-12:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-13:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-14:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-15:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-16:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-17:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-18:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-19:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-20:/home/ubuntu/stapl"

clean:
	cd ../stapl-core; mvn clean install
	cd ../stapl-templates; mvn clean install
	cd ../stapl-java-api; mvn clean install
	cd ../stapl-examples; mvn clean install
	# back in the original folder
	mvn clean package
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-client-1:/home/ubuntu/stapl
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-client-2:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-coordinator-c2:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-coordinator-c4:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-coordinator-c8:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-1:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-2:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-3:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-4:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-5:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-6:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-7:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-8:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-9:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-10:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-11:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-12:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-13:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-14:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-15:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-16:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-17:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-18:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-19:/home/ubuntu/stapl"
	ssh ubuntu@stapl-client-1 "scp -oStrictHostKeyChecking=no /home/ubuntu/stapl/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl-worker-20:/home/ubuntu/stapl"
