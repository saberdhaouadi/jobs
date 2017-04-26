{ name
, queuedThreshold ? 3600
, ...
}:
let
  inherit (import <nixpkgs> {}) lib;

  env = (import ./environments.nix)."${name}";
  queues = map (w: lib.replaceChars ["."] ["-"] w) (builtins.attrNames env.workers);

  creds = {
    appKey = builtins.readFile <global_creds/datadog-app-key>;
    apiKey = builtins.readFile <global_creds/datadog-api-key>;
  };

  perQueueGraph = title: toMetric:
    { inherit title;
      definition = builtins.toJSON {
        viz = "timeseries";
        requests = map (q: { q = toMetric (dash-to-underscore q); type = "line"; }) queues;
        autoscale = true;
      };
    };

  dash-to-underscore = lib.replaceChars ["-"] ["_"];
in
{
  resources.datadogTimeboards."lb-jobs-${name}" = 
    { title = "LB Jobs (${name})";
      description = "";
      graphs = [
        (perQueueGraph "Queued jobs" (q: "max:lb.steve.queued.${q}{host:database-${name}}"))
        (perQueueGraph "Running jobs" (q: "max:lb.steve.running.${q}{host:database-${name}}"))
        (perQueueGraph "Maximum queued time" (q: "max:lb.steve.queued_time.${q}.max{host:database-${name}}"))
        (perQueueGraph "Queued jobs (hourly delta)" (q: "max:lb.steve.queued.${q}{host:database-${name}} - hour_before(max:lb.steve.queued.${q}{host:database-${name}})"))

        { title = "Estimated Charges";
          definition = builtins.toJSON {
            viz = "timeseries";
            requests = [
              {
                q = "avg:lb.steve.estimated_charges{*}";
                type = "line";
              }
            ];
            autoscale = true;
          };
        }

        { title = "Response times";
          definition = builtins.toJSON {
            viz = "timeseries";
            requests = [
              {
                q = "avg:lb.web.responses.time.avg{host:steve-${name}}";
                type = "line";
              }
              {
                q = "avg:lb.web.responses.time.avg{host:database-${name}}";
                type = "line";
              }
            ];
            autoscale = true;
          };
        }

        { title = "Requests / s";
          definition = builtins.toJSON {
            viz = "timeseries";
            requests = [
              {
                q = "max:nginx.net.request_per_s{host:steve-${name}}";
                type = "line";
              }
              {
                q = "max:lb.web.responses.total{host:database-${name}}.as_rate()";
                type = "line";
              }
              {
                q = "max:lb.web.responses.total{host:steve-${name}}.as_rate()";
                type = "line";
              }
            ];
            autoscale = true;
          };
        }

        { title = "Batcher sizes";
          definition = builtins.toJSON {
            viz = "timeseries";
            requests = [
              {
                q = "avg:lb.steve.internal.batcher.read.size.avg{host:steve-${name}}";
                type = "line";
              }
              {
                q = "avg:lb.steve.internal.batcher.write.size.avg{host:steve-${name}}";
                type = "line";
              }
            ];
            autoscale = true;
          };
        }

        { title = "500 errors";
          definition = builtins.toJSON {
            viz = "timeseries";
            requests = [
              {
                q = "avg:lb.web.responses.5xx{host:steve-${name}}.as_count()";
                type = "bars";
              }
            ];
            autoscale = true;
          };
        }

        { title = "Memory usage lb-server / system";
          definition = builtins.toJSON {
            viz = "timeseries";
            requests = [
              {
                q = "avg:system.mem.usable{host:database-${name}}";
                type = "line";
              }
              {
                q = "avg:system.processes.mem.rss{host:database-${name},process_name:lb-server}";
                type = "line";
              }
            ];
            autoscale = true;
          };
        }
      ];
    } // creds;


  resources.datadogMonitors =
    lib.listToAttrs (map (q: 
      lib.nameValuePair
        "queued-builds-${q}-monitor" 
        ({
          name = "Queued builds (${q}/${name}) longer than ${toString queuedThreshold}s";
          type = "metric alert";
          message = "@lb-jobs@logicblox.com @opsgenie-lb_jobs";
          query = "avg(last_5m):max:lb.steve.queued_time.${dash-to-underscore q}.max{host:database-${name}} > ${toString queuedThreshold}";
          monitorOptions = builtins.toJSON {
            no_data_timeframe = 10;
            thresholds.critical = queuedThreshold;
          };
        } // creds)) queues);

}
