dnetcloud:
	mvn clean package
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-coordinator:/home/ubuntu/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-client-1:/home/ubuntu/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-client-2:/home/ubuntu/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-worker-1:/home/ubuntu/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-worker-2:/home/ubuntu/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-worker-3:/home/ubuntu/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar ubuntu@stapl-worker-4:/home/ubuntu/stapl
