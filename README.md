# ISW4 Proyecto Final - SITM-MIO

Proyecto final de Ingenieria de Software IV para calcular velocidades promedio del sistema SITM-MIO y comparar una evolucion arquitectonica desde ejecucion local hasta una version distribuida con ZeroC ICE.

## Estructura Del Repositorio

```text
ISW4-ProyFinal/
├── src/                         # Versiones locales V1/V2
│   ├── main/java/co/icesi/project/
│   │   ├── app/                 # Punto de entrada local
│   │   ├── controller/          # Orquestacion de procesamiento local
│   │   ├── model/               # Entidades de dominio
│   │   ├── repository/          # Lectura de CSV
│   │   ├── service/             # Calculo de velocidades
│   │   ├── util/                # Utilidades de fechas
│   │   └── view/                # Exportacion de resultados
│   └── test/java/               # Pruebas unitarias
├── v3-distributed/              # Version 3 distribuida con ZeroC ICE
│   ├── slice/                   # Contratos Slice .ice e interfaces generadas
│   ├── broker/                  # SpeedCalculationBroker
│   ├── master/                  # SpeedCalculationMaster
│   ├── worker/                  # SpeedPartitionWorker
│   ├── client/                  # AnalystClient
│   ├── visualization/           # BusEventMonitor
│   ├── partitioner/             # DatagramPartitioner
│   ├── config/                  # Configuracion base por proceso
│   ├── *.sh                     # Scripts Linux de despliegue
│   ├── README.md                # Guia tecnica de V3
│   └── DEPLOYMENT_NOTES.md      # Especificacion de despliegue
├── data/                        # Datos de entrada versionables pequenos
├── doc/                         # Informes y documentos de entrega
├── gradle/                      # Gradle Wrapper
├── build.gradle                 # Build raiz y V1/V2
├── settings.gradle              # Modulos Gradle
└── .gitignore                   # Exclusiones de artefactos y datos grandes
```

## Versiones

| Version | Ubicacion | Proposito |
|---|---|---|
| V1 | `src/main/java` | Procesamiento local base |
| V2 | `src/main/java` | Procesamiento local optimizado con concurrencia |
| V3 | `v3-distributed` | Procesamiento distribuido con Broker, Master-Worker, ICE y visualizacion de eventos |

## Artefactos Que No Se Versionan

No se deben subir al repositorio:

- `build/`, `.gradle/`, `bin/` y cualquier `.class`.
- `output/`, porque contiene resultados generados.
- `data/datagrams*.csv`, porque el dataset real es pesado.
- `data/partitions-*/`, porque son particiones generadas por despliegue.
- `v3-distributed/config/runtime-*.cfg`, porque son configuraciones temporales por maquina.

El archivo `data/lines-241-ActiveGT.csv` si se versiona porque es pequeno y necesario para mapear rutas.

## Build

```bash
./gradlew build
```

En Windows local:

```powershell
.\gradlew.bat build
```

## Despliegue V3

La guia de despliegue esta en:

```text
v3-distributed/DEPLOYMENT_NOTES.md
```

Para una prueba Linux local:

```bash
bash v3-distributed/prepartition.sh 8
bash v3-distributed/start-all.sh 8
```
