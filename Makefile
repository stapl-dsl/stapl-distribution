dnetcloud:
	mvn clean package
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-coordinator:/home/fedora/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-client-1:/home/fedora/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-client-2:/home/fedora/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-worker-1:/home/fedora/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-worker-2:/home/fedora/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-worker-3:/home/fedora/stapl
	scp target/stapl-distribution-0.0.1-SNAPSHOT-allinone.jar fedora@stapl-worker-4:/home/fedora/stapl
