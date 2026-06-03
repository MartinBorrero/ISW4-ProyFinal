# Como convertir la version secuencial en concurrente

Este documento explica como se podia transformar por cuenta propia el calculo de velocidades promedio desde una version secuencial hacia una version concurrente usando un `ThreadPool`.

El objetivo no es simplemente "meter hilos", sino dividir el trabajo de forma correcta, evitar condiciones de carrera y conservar el mismo resultado de la version original.

## 1. Entender primero que hace la version secuencial

Antes de hacer concurrencia, hay que identificar el flujo actual:

1. Cargar rutas activas desde el archivo CSV.
2. Cargar datagramas desde el archivo CSV.
3. Filtrar datagramas que pertenecen a rutas activas.
4. Ordenar los datagramas por bus, ruta y fecha.
5. Recorrer pares consecutivos de datagramas del mismo bus y la misma ruta.
6. Calcular distancia, tiempo y velocidad.
7. Descartar velocidades invalidas o mayores a `80 km/h`.
8. Agrupar por ruta y mes.
9. Calcular el promedio.
10. Exportar a Excel.

La parte mas costosa y mas facil de paralelizar es el calculo de velocidades, no la exportacion a Excel.

## 2. Identificar que datos se pueden procesar de forma independiente

La regla principal del calculo es esta:

```java
if (previous.getBusId() != current.getBusId()
        || previous.getLineId() != current.getLineId()) {
    previous = current;
    continue;
}
```

Esto significa que solo se comparan datagramas consecutivos del mismo bus y la misma ruta.

Por lo tanto, cada grupo `busId + lineId` se puede procesar de forma independiente.

Ejemplo:

```text
Bus 1, Ruta 131 -> tarea independiente
Bus 2, Ruta 131 -> tarea independiente
Bus 1, Ruta 205 -> tarea independiente
Bus 4, Ruta 300 -> tarea independiente
```

Esa es la clave de la concurrencia en este proyecto.

No conviene partir la lista simplemente por mitades, porque un bus podria quedar dividido entre dos hilos y se perderia la relacion entre datagramas consecutivos.

## 3. Agrupar los datagramas antes de crear tareas

Primero se filtran los datagramas validos y se agrupan por `busId|lineId`:

```java
Map<String, List<Datagram>> datagramsByBusAndLine = new HashMap<>();

for (Datagram d : datagrams) {
    if (activeRouteIds.contains(d.getLineId())) {
        String key = d.getBusId() + "|" + d.getLineId();
        datagramsByBusAndLine
                .computeIfAbsent(key, k -> new ArrayList<>())
                .add(d);
    }
}
```

Con esto cada grupo contiene solo datagramas que pueden compararse entre si.

## 4. Decidir cuantos hilos usar

Una forma razonable es usar la cantidad de procesadores disponibles:

```java
int threadCount = Runtime.getRuntime().availableProcessors();
```

Pero no tiene sentido crear mas hilos que grupos de trabajo:

```java
int workers = Math.min(threadCount, datagramsByBusAndLine.size());
```

Tambien se protege el caso minimo:

```java
int workers = Math.max(1, workers);
```

## 5. Repartir grupos entre workers

Una vez agrupados los datagramas, se reparten los grupos entre varias particiones:

```java
List<List<Map.Entry<String, List<Datagram>>>> partitions = new ArrayList<>();

for (int i = 0; i < workers; i++) {
    partitions.add(new ArrayList<>());
}

int index = 0;
for (Map.Entry<String, List<Datagram>> entry : datagramsByBusAndLine.entrySet()) {
    partitions.get(index % workers).add(entry);
    index++;
}
```

Cada particion sera procesada por un hilo del pool.

## 6. Crear el ThreadPool

Para usar un pool de hilos en Java:

```java
ExecutorService executor = Executors.newFixedThreadPool(workers);
```

Luego se envia cada particion como una tarea:

```java
List<Future<Map<String, DoubleSummaryStatistics>>> futures = new ArrayList<>();

for (List<Map.Entry<String, List<Datagram>>> partition : partitions) {
    Callable<Map<String, DoubleSummaryStatistics>> task =
            () -> calculatePartialStats(partition);

    futures.add(executor.submit(task));
}
```

Se usa `Callable` y no `Runnable` porque cada tarea debe devolver un resultado parcial.

## 7. Calcular resultados parciales por hilo

Cada hilo trabaja con sus propios datos y su propio mapa local:

```java
private Map<String, DoubleSummaryStatistics> calculatePartialStats(
        List<Map.Entry<String, List<Datagram>>> groupedDatagrams) {

    Map<String, DoubleSummaryStatistics> groupedStats = new HashMap<>();

    for (Map.Entry<String, List<Datagram>> entry : groupedDatagrams) {
        List<Datagram> group = entry.getValue();
        group.sort(Comparator.comparing(Datagram::getDatagramDate));

        for (int i = 1; i < group.size(); i++) {
            Datagram previous = group.get(i - 1);
            Datagram current = group.get(i);

            // calcular distancia, tiempo y velocidad
            // agrupar por ruta y mes
        }
    }

    return groupedStats;
}
```

Esto evita que varios hilos escriban al mismo tiempo sobre el mismo `HashMap`.

## 8. Evitar condiciones de carrera

Un error comun seria hacer esto:

```java
Map<String, DoubleSummaryStatistics> groupedStats = new HashMap<>();

// varios hilos modificando groupedStats al mismo tiempo
groupedStats.computeIfAbsent(key, k -> new DoubleSummaryStatistics()).accept(speed);
```

Eso no es seguro, porque `HashMap` y `DoubleSummaryStatistics` no estan disenados para ser modificados concurrentemente sin proteccion.

La alternativa usada es mas limpia:

```text
Hilo 1 -> mapa parcial 1
Hilo 2 -> mapa parcial 2
Hilo 3 -> mapa parcial 3

Luego el hilo principal combina los mapas.
```

## 9. Combinar resultados parciales

Cuando todas las tareas terminan, se obtienen los resultados con `future.get()`:

```java
Map<String, DoubleSummaryStatistics> groupedStats = new HashMap<>();

for (Future<Map<String, DoubleSummaryStatistics>> future : futures) {
    Map<String, DoubleSummaryStatistics> partialStats = future.get();
    mergeStats(groupedStats, partialStats);
}
```

La mezcla se hace asi:

```java
private void mergeStats(
        Map<String, DoubleSummaryStatistics> target,
        Map<String, DoubleSummaryStatistics> source) {

    for (Map.Entry<String, DoubleSummaryStatistics> entry : source.entrySet()) {
        target.computeIfAbsent(entry.getKey(), k -> new DoubleSummaryStatistics())
                .combine(entry.getValue());
    }
}
```

`DoubleSummaryStatistics.combine(...)` permite sumar conteo, suma, minimo y maximo de otra estadistica.

## 10. Cerrar el ExecutorService

Siempre se debe cerrar el pool:

```java
finally {
    executor.shutdown();
}
```

Si no se cierra, la aplicacion puede quedarse viva porque los hilos del pool siguen activos.

## 11. Manejar errores de concurrencia

`future.get()` puede lanzar excepciones:

```java
try {
    // ejecutar tareas y recoger resultados
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new IllegalStateException("Speed calculation was interrupted", e);
} catch (ExecutionException e) {
    throw new IllegalStateException("Speed calculation failed", e.getCause());
}
```

Si ocurre `InterruptedException`, se restaura el estado de interrupcion con:

```java
Thread.currentThread().interrupt();
```

Eso es una buena practica en Java concurrente.

## 12. Comparar contra la version secuencial

Para verificar que la version concurrente no cambio el resultado, se puede dejar temporalmente un metodo secuencial:

```java
List<SpeedRecord> sequential =
        service.calculateAverageSpeedsSequential(datagrams, routes);

List<SpeedRecord> concurrent =
        service.calculateAverageSpeeds(datagrams, routes);
```

Despues se comparan:

```text
misma cantidad de registros
mismas rutas
mismos meses
mismos promedios aproximados
```

Tambien se pueden medir tiempos:

```java
long start = System.currentTimeMillis();
service.calculateAverageSpeeds(datagrams, routes);
long end = System.currentTimeMillis();

System.out.println("Tiempo: " + (end - start) + " ms");
```

## 13. Resumen del proceso

Para hacer la version concurrente por cuenta propia, los pasos eran:

1. Encontrar la parte pesada del programa.
2. Revisar si el trabajo se puede dividir sin romper la logica.
3. Elegir una unidad independiente de trabajo.
4. En este caso, la unidad correcta fue `busId + lineId`.
5. Agrupar los datos por esa unidad.
6. Crear un `ExecutorService`.
7. Enviar tareas con `Callable`.
8. Hacer que cada tarea devuelva resultados parciales.
9. Combinar los resultados en el hilo principal.
10. Cerrar el pool.
11. Probar que el resultado concurrente sea equivalente al secuencial.

## 14. Idea principal

La concurrencia correcta no consiste en ejecutar cualquier ciclo en varios hilos.

La parte importante es encontrar una separacion natural del problema donde cada hilo pueda trabajar sin depender de los otros.

En este proyecto, esa separacion natural es:

```text
un grupo de datagramas del mismo bus y la misma ruta
```

Por eso la version concurrente es segura y mantiene la logica original.
