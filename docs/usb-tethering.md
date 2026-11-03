# USB tethering and Shizuku

Starting or stopping USB tethering switches the phone's USB function, to RNDIS
or NCM and back. On many devices, including recent Samsung and OnePlus phones,
that restarts `adbd`. A Shizuku that was started through ADB is a child of
`adbd`, so it goes down with it, and takes the shell process that holds the
session along.

The troubleshooting page of the
[thedjchi Shizuku fork](https://github.com/thedjchi/Shizuku/wiki/Troubleshooting)
says the same about USB modes: switching them crashes Shizuku, and its watchdog
is the way to keep Shizuku alive. Shizzi's USB support was tested on a Pixel,
where the switch does not take Shizuku down.

## What it looks like

The in-app log shows, a fraction of a second after `vpn present at start`:

```
shell process died; recovering any downstream it left up
start failed in the app process: android.os.DeadObjectException: null
orphan recovery failed: could not reach Shizuku to drop the hotspot: binder haven't been received
```

Shizuku then reads "installed but not running" until it is started again. The
hotspot on its own is not affected, only USB.

To check a device, note `adb shell pidof adbd`, start a session with USB
tethering on, reconnect ADB and look again. A different PID means `adbd` was
restarted.

## What Shizzi does about it

- **USB that is already up is kept.** Stopping it and starting it again would
  switch the function twice, so its interfaces are untethered and tethered
  again instead. Tethering chooses its upstream when the first downstream comes
  up, so this still puts it on the test network.
- **A start that loses Shizuku waits for it.** The first USB start on such a
  phone does switch the function. Shizzi waits up to 45 seconds for Shizuku to
  come back, then starts again. USB tethering is up by then, so the second start
  leaves the function alone.
- **A stop survives the same restart.** Stopping USB tethering switches the
  function back. The shell process exiting during a stop counts as stopped.

## What you need

Shizuku has to come back. Either it restarts itself, which the watchdog of the
[thedjchi fork](https://github.com/thedjchi/Shizuku/wiki/Features#watchdog) does
(it runs when start on boot is enabled), or you start it again when Shizzi says
it is gone. In that case start sharing again afterwards: USB tethering is
already on, so it won't stop Shizuku a second time.

## Limits

- Unplugging the cable resets the USB function, and Shizzi switches it again
  when the cable comes back. On a phone that restarts `adbd` for that, a running
  session ends instead of riding it out.
- While Shizuku is away, USB clients that are still connected use the phone's
  normal connection, because the test network goes with the shell process.
