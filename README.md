# ISW4 Proyecto Final - V3 Distribuida

Implementacion distribuida para calcular velocidades promedio del sistema SITM-MIO usando ZeroC ICE.

## Estructura

```text
ISW4-ProyFinal/
├── data/
│   └── lines-241-ActiveGT.csv
├── gradle/
├── v3-distributed/
│   ├── slice/
│   ├── broker/
│   ├── master/
│   ├── worker/
│   ├── client/
│   ├── visualization/
│   ├── partitioner/
│   ├── config/
│   ├── deploy.env
│   ├── deploy-runtime-files.ps1
│   ├── distribute-partitions-from-master.sh
│   └── start-node.sh
├── build.gradle
├── settings.gradle
├── gradlew
└── gradlew.bat
```

## Componentes

- `visualization`: recibe eventos del sistema y muestra el monitoreo.
- `client`: envia la solicitud de procesamiento al broker.
- `broker`: recibe la solicitud del cliente y la delega al master.
- `master`: registra workers, asigna particiones y reduce resultados.
- `worker`: procesa una particion del datagrama.
- `partitioner`: genera particiones desde el datagrama completo.
- `slice`: contratos ICE compartidos entre los componentes.

## Compilacion

En Windows:

```powershell
.\gradlew.bat clean :v3-distributed:visualization:installDist :v3-distributed:client:installDist :v3-distributed:master:installDist :v3-distributed:broker:installDist :v3-distributed:worker:installDist :v3-distributed:partitioner:installDist
```

En Linux:

```bash
./gradlew clean :v3-distributed:visualization:installDist :v3-distributed:client:installDist :v3-distributed:master:installDist :v3-distributed:broker:installDist :v3-distributed:worker:installDist :v3-distributed:partitioner:installDist
```

## Despliegue

La configuracion del despliegue esta en:

```text
v3-distributed/deploy.env
```

Desde Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\v3-distributed\deploy-runtime-files.ps1
```

El script compila los componentes, copia los ejecutables a los equipos configurados, genera particiones en el equipo master y distribuye las particiones por la red interna.

## Arranque

Visualizacion:

```bash
bash v3-distributed/start-node.sh visualization
```

Workers:

```bash
bash v3-distributed/start-node.sh worker <numero>
```

Master y broker:

```bash
bash v3-distributed/start-node.sh coordination
```

Cliente:

```bash
bash v3-distributed/start-node.sh client
```
