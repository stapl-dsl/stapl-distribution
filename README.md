# STAPL Distribution

This repository contains the code to correctly evaluate STAPL policies concurrently or distributedly. To be clear, this is not some kind of distribution of STAPL :)

### Why do we need specialized components to evaluate STAPL policies concurrently?

Evaluating an access control policy concurrently or distributedly is trivial in case these policies only read data. History-based policies such as Chinese Wall policies however also update data as a result of policy evaluation. For example, take the following policy:

```
If a user has had access to documents of Bank A, he or she is not allowed to access documents of Bank B.
```

For these policies, one evaluation of the policy influences future evaluations, which leads to race conditions. As a result, if a user has not had access to documents of Bank A or Bank B, this user could exploit concurrency to have the policy permit two parallel requests, thus violating the policy. In short, we need concurrency control to ensure that correct concurrent or distributed evaluation of such history-based policies and this repository provides a scalable and efficient instantiation of such concurrency control.

For more information, we refer to our paper on the Annual Computer Security Applications Conference (ACSAC) 2015 (the link is coming soon).

### Building this code

The code in this repository depends on the other STAPL repositories. To install and compile this repository, first make sure you have the Java SDK, Git and Maven installed, then run:

```
git clone https://github.com/stapl-dsl/stapl-core.git
git clone https://github.com/stapl-dsl/stapl-templates.git
git clone https://github.com/stapl-dsl/stapl-java-api.git
git clone https://github.com/stapl-dsl/stapl-examples.git
git clone https://github.com/stapl-dsl/stapl-distribution.git
cd stapl-core
mvn install
cd ../stapl-templates
mvn install
cd ../stapl-java-api
mvn install
cd ../stapl-examples
mvn install
cd ../stapl-distribution
mvn package
```

These commands will generate `stapl-distribution-0.0.1-SNAPSHOT-allinone.jar` located in `stapl-distribution/target/`.

### Running the code

The code provides three different components that can separately be deployed on different machines (or the same machine) and can work together. These components are:

* A worker: this component will effectively evaluate the access control policies.
* A coordinator: this component performs concurrency control on the evaluations performed by the workers. There are two different coordinators: 
** a centralized coordinator that performs concurrency control by its own and,
** a distributed coordinator that collaborates with other distributed coordinators to perform concurrency control.
* A client: this component sends requests to the coordinators/workers and outputs statistics. There are currently three types of clients: 
** a sequential client that sends one request after the other has completed,
** a peak client that sends a number of requests at once,
** a continuous overload client that continuously keeps the system in overload but avoids overflowing queues.
* A foreman: this component manages multiple clients on a single machine without a coordinator.

To run the code, decide on which machines you want a client, a coordinator and/or a worker and deploy stapl-distribution-0.0.1-SNAPSHOT-allinone.jar on each of those machines. Then run each of these components by running the appropriate main class. The available classes are:

* `stapl.distribution.DistributedCoordinatorApp`: an instance of a distributed coordinator that can manage multiple workers. Run only 1 of these to effectively run a centralized coordinator.
* `stapl.distribution.ForemanApp`
* `stapl.distribution.SequentialClientForDistributedCoordinatorsApp`
* `stapl.distribution.InitialPeakClientForDistributedCoordinatorsApp`
* `stapl.distribution.ContinuousOverloadClientForDistributedCoordinatorsApp1`

Run these main classes with stapl-distribution-0.0.1-SNAPSHOT-allinone.jar on the class path, for example:

```
java -cp stapl-distribution-0.0.1-SNAPSHOT-linone.jar stapl.distribution.DistributedCoordinatorApp
```

Run each of these commands to view the required parameters.

Also make sure to have a MySQL database running containing a database called `stapl-attributes` to fetch or update attributes. The port and IP address of the MySQL database are given in the parameters to the main classes, the login credentials on the other hand are currently hard-coded as `root/root`. Use the main class `stapl.distribution.ResetDBApp` to initiate the database with an initial set of attributes.

So, as an example, to run a centralized coordinator with 3 workers and a sequential client on a single machine, run the following commands. 

First, reset the database:

```
java -cp stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl.distribution.ResetDBApp --database-ip 127.0.0.1 --database-port 3306
```

Then start the coordinator with three workers:

```
java -cp stapl-distribution-0.0.1-SNAPSHOT-allinone.jarstapl.distribution.DistributedCoordinatorApp --hostname coordinator.stapl --ip 127.0.0.1 --port 2552 --nb-workers 3 --nb-update-workers 1 --database-ip 127.0.0.1 --database-port 3306 --db-type mysql --policy ehealth --log-level INFO
```

Set `--log-level` to `DEBUG` to see debugging info about policy evaluations and concurrency control.

Then, in a different terminal on the same host, start the client:

```
java -cp stapl-distribution-0.0.1-SNAPSHOT-allinone.jar stapl.distribution.SequentialClientForDistributedCoordinatorsApp --ip 127.0.0.1 --port 0 --coordinator-manager-ip 127.0.0.1 --coordinator-manager-port 2552 --nb-threads 20 --nb-warmup-runs 100 --nb-runs 1000
```

This single command will start 20 parallel clients that will each send a requests one after the other. More precisely, each client will send 100 warm-up requests and then 1000 requests of which the statistics will be printed out in the end.

### Further questions?

To be clear, the code in this repository is still prototype-quality. If you have any questions, just ask.
