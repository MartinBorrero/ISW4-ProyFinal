# Especificación de despliegue de la versión 3 distribuida del sistema SITM-MIO

Ingeniería de Software IV  
Proyecto final  
Universidad Icesi  
Equipo de desarrollo

## Resumen

Este documento explica cómo desplegamos la versión 3 distribuida del sistema SITM-MIO. La guía está escrita como una especificación práctica para que cualquier integrante del equipo pueda preparar los equipos, copiar los artefactos, ejecutar los componentes y validar que el procesamiento distribuido funciona correctamente.

La versión 3 se diseñó para procesar el dataset piloto completo sin obligar a cada worker a leer el archivo de datagramas completo. Para lograrlo usamos una arquitectura distribuida con cliente, broker, master, workers, particionador y visualización.

## Contexto del despliegue

El objetivo del despliegue es ejecutar el cálculo de velocidad promedio de los buses por ruta y por mes. El cálculo se realiza para las rutas activas del archivo `lines-241-ActiveGT.csv` y usa como entrada el dataset `datagrams4Pilot.csv`.

En nuestro montaje, el archivo grande no se copia a todos los computadores. El datagrama completo queda en el equipo coordinador, se generan particiones allí y luego cada worker recibe únicamente la partición que debe procesar. Esta decisión reduce transferencia de archivos, evita duplicar datos pesados y mantiene la correctitud del cálculo.

## Decisiones arquitectónicas

La arquitectura usa los siguientes patrones y estilos:

- Arquitectura distribuida: los componentes se ejecutan en varios computadores conectados por red.
- Broker: el cliente se comunica con el broker y no necesita conocer directamente al master ni a los workers.
- Master-Worker: el master registra workers, reparte particiones y consolida resultados.
- Particionamiento de datos: el dataset se divide por la clave `busId|lineId` para conservar juntos los datagramas del mismo bus en la misma ruta.
- Map-Reduce simplificado: los workers calculan resultados parciales y el master reduce esos resultados en un CSV final.
- Eventos para visualización: los workers, broker y master publican eventos que permiten observar el comportamiento del sistema.

Estas decisiones están relacionadas con los drivers de performance y escalabilidad, porque el sistema puede aumentar el número de workers y repartir mejor el trabajo. También apoyan la correctitud, porque cada worker procesa secuencias consistentes de datagramas del mismo bus en una ruta.

## Equipos y roles

El despliegue actual usa doce procesos principales distribuidos en la red del laboratorio. Los roles quedan organizados así:

- PC de visualización y cliente: `192.168.131.131`. Ejecuta `Visualization` y `Client`.
- PC coordinador: `192.168.131.101`. Ejecuta `Master`, `Broker` y `Partitioner`.
- Workers: `192.168.131.102`, `192.168.131.103`, `192.168.131.104`, `192.168.131.105`, `192.168.131.118`, `192.168.131.120`, `192.168.131.121`, `192.168.131.128`, `192.168.131.129` y `192.168.131.130`.

Para copiar archivos por SSH usamos el usuario `swarch` y la ruta remota `/home/swarch/Documents/JCMMJ`. Las IP de ZeroTier se usan para copiar archivos desde nuestro computador, mientras que las IP `192.168.131.x` se usan para comunicación interna entre los nodos.

## Configuración principal

El archivo `v3-distributed/deploy.env` centraliza la configuración del despliegue. En este archivo definimos cantidad de particiones, hosts, puertos, ruta remota y parámetros de ejecución.

- `PARTITIONS=10`: se generan diez particiones porque se usan diez workers.
- `MAX_ROWS=0`: el valor cero indica que se procesa todo el dataset.
- `WORKER_HEAP=4096m`: cada worker puede usar hasta 4 GB de heap para el proceso Java.
- `BROKER_PORT=11000`, `MASTER_PORT=11001` y `VISUALIZATION_PORT=11003`: estos puertos se escogieron para evitar conflictos con procesos anteriores.
- `WORKER_BASE_PORT=11010`: el puerto de cada worker se calcula como `WORKER_BASE_PORT + número del worker`.

## Preparación local

Antes de copiar archivos a los equipos, compilamos la distribución desde el computador donde está el código fuente. El comando se ejecuta desde la raíz del repositorio:

```powershell
.\gradlew.bat :v3-distributed:visualization:installDist :v3-distributed:client:installDist :v3-distributed:master:installDist :v3-distributed:broker:installDist :v3-distributed:worker:installDist :v3-distributed:partitioner:installDist
```

Si la compilación termina con `BUILD SUCCESSFUL`, los ejecutables quedan dentro de las carpetas `build/install` de cada módulo.

## Copia de archivos

La copia se hace con `scp`. En condiciones normales se copian los scripts, `deploy.env` y los builds de cada componente al equipo correspondiente. Cuando un equipo no responde por ZeroTier, usamos un equipo accesible como puente mediante `ProxyJump`.

Al equipo `192.168.131.101` se envían master, broker, partitioner, scripts de coordination y `deploy.env`. Al equipo `192.168.131.131` se envían visualization, client, scripts de arranque y `deploy.env`. A cada worker se envía el build de worker, `start-worker.sh`, `start-node.sh` y `deploy.env`.

Las particiones no se copian desde nuestro computador. El equipo coordinador las genera a partir de `datagrams4Pilot.csv` y luego las distribuye por la red interna a cada worker. Esto evita mover repetidamente el archivo completo desde el equipo local.

## Generación y distribución de particiones

El archivo `datagrams4Pilot.csv` debe existir previamente en el coordinador en la ruta:

```text
/home/swarch/Documents/JCMMJ/data/datagrams4Pilot.csv
```

El script de despliegue verifica esta condición cuando `COPY_FULL_DATAGRAM_TO_MASTER=false`.

Después, el particionador genera `data/partitions-10`. Cada partición se asigna de forma fija al worker correspondiente: worker 1 procesa `partition-0.csv`, worker 2 procesa `partition-1.csv` y así sucesivamente hasta worker 10, que procesa `partition-9.csv`.

## Orden de ejecución

El orden de arranque recomendado es el siguiente:

1. Primero se inicia `Visualization` en `192.168.131.131`.
2. Después se inician todos los workers con su número correspondiente.
3. Luego se inicia `coordination` en `192.168.131.101`, lo que levanta `Master` y `Broker`.
4. Finalmente se ejecuta `Client` en `192.168.131.131` para lanzar la tarea distribuida.

Los workers pueden iniciar antes que el master. Si el master aún no está disponible, los workers reintentan el registro automáticamente hasta que logran conectarse.

## Comandos de ejecución

En el equipo de visualización se ejecuta:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh visualization
```

En cada worker se ejecuta, cambiando `N` por el número del worker:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh worker N
```

En el coordinador se ejecuta:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh coordination
```

Finalmente, en el equipo del cliente se ejecuta:

```bash
cd /home/swarch/Documents/JCMMJ
bash v3-distributed/start-node.sh client
```

## Resultado esperado

El resultado final se escribe desde el master, por lo que el archivo queda en el equipo `192.168.131.101`, en la ruta:

```text
/home/swarch/Documents/JCMMJ/output/resultados-v3.csv
```

El CSV final contiene las columnas `routeId`, `month`, `averageSpeed` y `count`. Internamente, los workers calculan velocidades por bus y ruta, acumulan primero por día y luego consolidan por mes antes de enviar los parciales al master.

## Validación

Para validar el despliegue revisamos cuatro evidencias:

- Cada worker debe mostrar que fue registrado con el master.
- El cliente debe terminar con `Success: true`.
- El archivo `output/resultados-v3.csv` debe existir en el coordinador.
- La visualización debe mostrar eventos de ejecución y posiciones de buses.

La visualización permite filtrar por mes y limitar la cantidad de buses renderizados. Este filtro no cambia el cálculo; solo evita que el mapa se sature durante la demostración.

## Problemas frecuentes y solución

- Si aparece `Address already in use`, significa que hay un proceso anterior usando el puerto. Se debe cerrar la ejecución previa o cambiar el puerto en `deploy.env`.
- Si un worker espera al master, se debe verificar que `coordination` esté corriendo en `192.168.131.101` y que el puerto `11001` esté disponible.
- Si el cliente termina pero no se ve el output en `192.168.131.131`, se debe buscar el archivo en el coordinador `192.168.131.101`.
- Si la visualización no abre ventana por PuTTY, se debe ejecutar en una sesión gráfica del equipo o usar X11 forwarding. Por consola solo se verán eventos de texto.

## Relación con los atributos de calidad

La solución responde al atributo de performance porque reparte el procesamiento entre varios workers y evita que todos lean el archivo completo. Responde al atributo de escalabilidad porque permite aumentar el número de workers cambiando `PARTITIONS` y la lista de hosts. Responde a correctitud porque conserva juntos los datagramas de cada bus en cada ruta, calcula velocidades entre puntos consecutivos y consolida los resultados por día y luego por mes.

## Referencias

OpenStreetMap contributors. (s. f.). *OpenStreetMap*. https://www.openstreetmap.org/

ZeroC. (s. f.). *Ice documentation*. https://zeroc.com/ice
