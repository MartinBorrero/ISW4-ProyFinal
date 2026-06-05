module sitmmio {
module v3 {
module slice {

struct SpeedTask {
    string taskId;
    string datagramsPath;
    string routesPath;
    string outputPath;
    int maxRows;
    int partitionIndex;
    int partitionCount;
};

struct SpeedStat {
    int lineId;
    string month;
    long count;
    double sum;
};

sequence<SpeedStat> SpeedStatSeq;
sequence<string> StringSeq;

struct SpeedResult {
    string taskId;
    bool success;
    string message;
    string outputPath;
    int records;
    long elapsedMs;
    SpeedStatSeq stats;
};

struct BusEvent {
    string source;
    string destination;
    string messageType;
    string timestamp;
    string detail;
};

interface Worker {
    string workerId();
    SpeedResult process(SpeedTask task);
};

interface Master {
    void registerWorker(string workerId, Worker* worker);
    void unregisterWorker(string workerId);
    SpeedResult execute(SpeedTask task);
    StringSeq activeWorkers();
};

interface Broker {
    SpeedResult submit(SpeedTask task);
};

interface Visualization {
    void publish(BusEvent event);
};

};
};
};
