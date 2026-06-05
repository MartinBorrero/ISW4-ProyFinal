# SITM-MIO Version 3 Distributed ICE

This folder contains the distributed implementation of the SITM-MIO average-speed calculation pipeline for Linux deployment.

## Distributed Component Names

Use these names in UML deployment diagrams:

| Technical name | Diagram name |
|---|---|
| Client | AnalystClient |
| Visualization | BusEventMonitor |
| Broker | SpeedCalculationBroker |
| Master | SpeedCalculationMaster |
| Worker | SpeedPartitionWorker |
| Partitioner | DatagramPartitioner |
| Partition files | PartitionedDatagramStore |
| Output CSV | SpeedResultsStore |

The code keeps `Broker`, `Master` and `Worker` names so the architectural patterns remain directly recognizable.

## Flexible Deployment

Choose the number of processing nodes `N`, prepartition into `N` files, and start `N` workers.

```text
Total computers = N workers + 2
PC 1: AnalystClient + BusEventMonitor
PC 2: SpeedCalculationBroker + SpeedCalculationMaster
PC 3..PC(N+2): SpeedPartitionWorkers
```

Recommended experiment sizes:

```text
N=2  -> 4 computers
N=4  -> 6 computers
N=8  -> 10 computers, recommended
N=12 -> 14 computers
```

## Build

```bash
./gradlew build
```

## Prepartition

```bash
bash v3-distributed/prepartition.sh 8
bash v3-distributed/prepartition.sh 12
bash v3-distributed/prepartition.sh 8 1000000
```

Output:

```text
data/partitions-N/partition-0.csv
...
data/partitions-N/partition-(N-1).csv
```

## Start Local Demo

```bash
bash v3-distributed/start-all.sh 8
```

## Start Multi-Computer Deployment

Visualization computer:

```bash
bash v3-distributed/start-visualization.sh <VISUALIZATION_IP>
```

Worker computer:

```bash
bash v3-distributed/start-worker.sh worker-<N> <PORT> <MASTER_IP> <WORKER_IP> <VISUALIZATION_IP>
```

Coordination computer:

```bash
bash v3-distributed/start-master.sh <MASTER_IP> <VISUALIZATION_IP>
bash v3-distributed/start-broker.sh <BROKER_IP> <MASTER_IP> <VISUALIZATION_IP>
```

Client computer:

```bash
bash v3-distributed/start-client.sh <BROKER_IP> <PARTITION_COUNT> data/partitions-<PARTITION_COUNT>
```

## Detailed Deployment Document

See:

```text
v3-distributed/DEPLOYMENT_NOTES.md
doc/Especificacion-Despliegue-V3.docx
```

## Patterns

- Broker: `broker/BrokerI.java`, diagram name `SpeedCalculationBroker`.
- Master-Worker: `master/MasterI.java`, `worker/WorkerServer.java`, `worker/WorkerI.java`.
- Pipe-and-filter: prepartition, dispatch, worker calculation, reduce, export.
- Event-driven visualization: `visualization/VisualizationI.java`, diagram name `BusEventMonitor`.

## Graphical Bus Visualization

`BusEventMonitor` opens a Swing GUI with two real-time sections:

- A real Cali map panel using OpenStreetMap tiles. It plots sampled SITM-MIO bus positions using latitude and longitude from the datagram partitions processed by workers.
- A message-bus event table showing `Client -> Broker -> Master -> Worker` events with source, destination, type, timestamp and detail.

Workers publish `BUS_POSITION` events asynchronously while reading their assigned partition. This keeps the map live without blocking the distributed calculation pipeline.

The map animates each bus toward its latest reported coordinate and draws a short movement trail. OpenStreetMap tiles require internet access on the `BusEventMonitor` computer; if tiles cannot be loaded, events and bus positions still continue to be processed.

## Correctness Rule

Partition by:

```text
busId|lineId
```

This keeps all consecutive GPS points of a bus-route pair in the same worker partition.
