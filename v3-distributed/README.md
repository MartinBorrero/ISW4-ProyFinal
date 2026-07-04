# Despliegue V3
Nota: los .env ya se encuentran en cada equipo 
## Despliegue Rapido

1. Compilar desde la maquina local:

```powershell
.\gradlew.bat :v3-distributed:visualization:installDist :v3-distributed:client:installDist :v3-distributed:master:installDist :v3-distributed:broker:installDist :v3-distributed:worker:installDist :v3-distributed:partitioner:installDist
```

2. Copiar a cada equipo solo lo que necesita. Si solo cambian los `.env` o este README, no hay que reenviar los builds.

```text
Visualization: 192.168.131.118
Master/Broker: 192.168.131.129
Client:        192.168.131.131
Worker 1:      192.168.131.128
Worker 2:      192.168.131.127
Worker 3:      192.168.131.126
```

Si se tienen los scripts y builds generados localmente, se puede copiar manualmente con `scp` normal.

Primero generar los `.env` minimos:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\v3-distributed\deploy-runtime-files.ps1 -SkipBuild -SkipClient -SkipVisualization -SkipMaster -SkipPartitionGeneration -SkipWorkers -SkipPartitionDistribution -SkipPermissions
```

Variables para PowerShell:

```powershell
$USER="swarch"
$ROOT="/home/swarch/Documents/JCMMJ"
```

Visualizacion (`192.168.131.118`):

```powershell
scp -r v3-distributed/visualization/build "$USER@192.168.131.118:$ROOT/v3-distributed/visualization/"
scp v3-distributed/start-node.sh v3-distributed/start-visualization.sh build/tmp/deploy-env/visualization/deploy.env v3-distributed/README.md "$USER@192.168.131.118:$ROOT/v3-distributed/"
ssh "$USER@192.168.131.118" "cd '$ROOT' && sed -i 's/\r$//' v3-distributed/*.sh v3-distributed/*.env && chmod +x v3-distributed/*.sh v3-distributed/visualization/build/install/visualization/bin/*"
```

Master/Broker (`192.168.131.129`):

```powershell
scp -r v3-distributed/master/build "$USER@192.168.131.129:$ROOT/v3-distributed/master/"
scp -r v3-distributed/broker/build "$USER@192.168.131.129:$ROOT/v3-distributed/broker/"
scp -r v3-distributed/partitioner/build "$USER@192.168.131.129:$ROOT/v3-distributed/partitioner/"
scp v3-distributed/start-node.sh v3-distributed/start-master.sh v3-distributed/start-broker.sh v3-distributed/prepartition.sh v3-distributed/distribute-partitions-from-master.sh build/tmp/deploy-env/coordination/deploy.env v3-distributed/README.md "$USER@192.168.131.129:$ROOT/v3-distributed/"
scp data/lines-241-ActiveGT.csv "$USER@192.168.131.129:$ROOT/data/"
ssh "$USER@192.168.131.129" "cd '$ROOT' && sed -i 's/\r$//' v3-distributed/*.sh v3-distributed/*.env && chmod +x v3-distributed/*.sh v3-distributed/*/build/install/*/bin/*"
```

Cliente (`192.168.131.131`):

```powershell
scp -r v3-distributed/client/build "$USER@192.168.131.131:$ROOT/v3-distributed/client/"
scp v3-distributed/start-node.sh v3-distributed/start-client.sh build/tmp/deploy-env/client/client.env v3-distributed/README.md "$USER@192.168.131.131:$ROOT/v3-distributed/"
scp data/lines-241-ActiveGT.csv "$USER@192.168.131.131:$ROOT/data/"
ssh "$USER@192.168.131.131" "cd '$ROOT' && sed -i 's/\r$//' v3-distributed/*.sh v3-distributed/*.env && chmod +x v3-distributed/*.sh v3-distributed/client/build/install/client/bin/*"
```

Workers (`192.168.131.128`, `192.168.131.127`, `192.168.131.126`):

```powershell
$WORKERS=@(
"192.168.131.128",
"192.168.131.127",
"192.168.131.126"
)

foreach ($HOSTNAME in $WORKERS) {
  scp -r v3-distributed/worker/build "$USER@${HOSTNAME}:$ROOT/v3-distributed/worker/"
  scp v3-distributed/start-node.sh v3-distributed/start-worker.sh build/tmp/deploy-env/worker/deploy.env v3-distributed/README.md "$USER@${HOSTNAME}:$ROOT/v3-distributed/"
  scp data/lines-241-ActiveGT.csv "$USER@${HOSTNAME}:$ROOT/data/"
  ssh "$USER@$HOSTNAME" "cd '$ROOT' && sed -i 's/\r$//' v3-distributed/*.sh v3-distributed/*.env && chmod +x v3-distributed/*.sh v3-distributed/worker/build/install/worker/bin/*"
}
```

<h2 style="color: red;">EMPEZAR DESDE AQUI</h2>

Desde este punto se preparan los datos para ejecutar el sistema distribuido.

3. En el Master (`192.168.131.129`), generar particiones:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/prepartition.sh --datagrams data/datagrams4Pilot.csv
```
NOta: se cambia al final del script el nombre del dataset
El script calcula automaticamente la cantidad de particiones desde `WORKER_HOSTS`. Actualmente genera 3 particiones.

4. En el Master (`192.168.131.129`), enviar particiones a los workers:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/distribute-partitions-from-master.sh
```

5. Ejecutar los procesos en este orden:

```text
1. Visualization
2. Workers
3. Master/Broker
4. Client
```

## Orden De Ejecucion

1. Visualization - ejecutar en `192.168.131.118`:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh visualization
```

2. Workers - ejecutar cada comando en su computador:

```bash
# 192.168.131.128
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh worker 1

# 192.168.131.127
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh worker 2

# 192.168.131.126
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh worker 3
```

3. Master/Broker - ejecutar en `192.168.131.129`:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh coordination
```

4. Client - ejecutar en `192.168.131.131`:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh client
```

## Puertos

```text
Broker:        11000
Master:        11001
Visualization: 11003
Workers:       11011, 11012, 11013
```
```bash
lsof -i :<Puerto Master>
kill -9 <PID proceso Master>
```
```text
Anter de ejecutar el coordination.sh en el nodo de coordinador (Master), verificar que el puerto para el Master no se encuentre ocupado. Si lo esta, matar el proceso del puerto y luego ejecutar el comando .sh
```


```text
Los datasets que toma se encuentran en la siguiente ruta del equipo 29:
```

```bash
cd /home/swarch/Documents/JCMMJ/data
```

```text
Los .env con la info que requieren ya están cada uno en su respectivo equipo.
```

```text
Para generar las particiones, ejecutar el siguiente comando en el nodo de procesamiento coordinador (Master):

v3-distributed/partitioner/build/install/partitioner/bin/partitioner \
  --datagrams data/datagrams4Pilot.csv \
  --routes data/lines-241-ActiveGT.csv \
  --output data/partitions-3 \
  --partitions 3

El .csv a particionar debe estar ubicado en la ruta: /home/swarch/Documents/JCMMJ/data/. Además, cambiar en la linea " --datagrams data/datagrams4Pilot.csv " el nombre del csv al que desea particionar.

Para distribuir las particiones, ejecutar el siguiente comando en el nodo de procesamiento coordinador (Master):

bash v3-distributed/distribute-partitions-from-master.sh

Deberá escribir la contraseña del usuario swarch a nmedida que se realiza el envio por scp a los workers respectivos
```

Con este comando tambien se generan particiones pero mira directamente la cantidad de workers sin embargo, no hay que ponerla en el comando de ejecución
```bash
bash v3-distributed/prepartition.sh --datagrams data/datagrams4Pilot.csv
```
