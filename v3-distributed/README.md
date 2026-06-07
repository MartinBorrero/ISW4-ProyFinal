# SITM-MIO V3 Distributed ICE

Implementacion distribuida de la version 3.

## Componentes

- `visualization`: monitor de eventos y mapa.
- `client`: envia la tarea al broker.
- `broker`: punto de entrada del cliente.
- `master`: registra workers, asigna particiones y reduce resultados.
- `worker`: procesa una particion.
- `partitioner`: genera `data/partitions-N` desde `data/datagrams4Pilot.csv`.
- `slice`: contratos ICE compartidos.

## Compilar

```bash
./gradlew :v3-distributed:visualization:installDist :v3-distributed:client:installDist :v3-distributed:master:installDist :v3-distributed:broker:installDist :v3-distributed:worker:installDist :v3-distributed:partitioner:installDist
```

## Despliegue Actual

La configuracion esta en:

```text
v3-distributed/deploy.env
```

Desde Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\v3-distributed\deploy-runtime-files.ps1
```

El datagrama grande debe estar previamente en PC2:

```text
/home/swarch/Documents/JCMMJ/data/datagrams4Pilot.csv
```

El script copia ejecutables, genera particiones en PC2 y distribuye cada particion al worker correspondiente.

## Arranque

PC1:

```bash
bash v3-distributed/start-node.sh visualization
```

Workers:

```bash
bash v3-distributed/start-node.sh worker <numero>
```

PC2:

```bash
bash v3-distributed/start-node.sh coordination
```

PC1:

```bash
bash v3-distributed/start-node.sh client
```
