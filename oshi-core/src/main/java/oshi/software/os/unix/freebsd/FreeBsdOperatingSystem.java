/**
 * Oshi (https://github.com/oshi/oshi)
 *
 * Copyright (c) 2010 - 2018 The Oshi Project Team
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Maintainers:
 * dblock[at]dblock[dot]org
 * widdis[at]gmail[dot]com
 * enrico.bianchi[at]gmail[dot]com
 *
 * Contributors:
 * https://github.com/oshi/oshi/graphs/contributors
 */
package oshi.software.os.unix.freebsd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import oshi.jna.platform.linux.Libc;
import oshi.software.common.AbstractOperatingSystem;
import oshi.software.os.FileSystem;
import oshi.software.os.NetworkParams;
import oshi.software.os.OSProcess;
import oshi.util.ExecutingCommand;
import oshi.util.LsofUtil;
import oshi.util.MapUtil;
import oshi.util.ParseUtil;
import oshi.util.platform.unix.freebsd.BsdSysctlUtil;

/**
 * Linux is a family of free operating systems most commonly used on personal
 * computers.
 *
 * @author widdis[at]gmail[dot]com
 */
public class FreeBsdOperatingSystem extends AbstractOperatingSystem {
    private static final long serialVersionUID = 1L;

    public FreeBsdOperatingSystem() {
        this.manufacturer = "Unix/BSD";
        this.family = BsdSysctlUtil.sysctl("kern.ostype", "FreeBSD");
        this.version = new FreeBsdOSVersionInfoEx();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FileSystem getFileSystem() {
        return new FreeBsdFileSystem();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OSProcess[] getProcesses(int limit, ProcessSort sort) {
        List<OSProcess> procs = getProcessListFromPS(
                "ps -awwxo state,pid,ppid,user,uid,group,gid,nlwp,pri,vsz,rss,etimes,systime,time,comm,args", -1);
        List<OSProcess> sorted = processSort(procs, limit, sort);
        return sorted.toArray(new OSProcess[sorted.size()]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OSProcess getProcess(int pid) {
        List<OSProcess> procs = getProcessListFromPS(
                "ps -awwxo state,pid,ppid,user,uid,group,gid,nlwp,pri,vsz,rss,etimes,systime,time,comm,args -p ", pid);
        if (procs.isEmpty()) {
            return null;
        }
        return procs.get(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OSProcess[] getChildProcesses(int parentPid, int limit, ProcessSort sort) {
        List<OSProcess> procs = getProcessListFromPS(
                "ps -awwxo state,pid,ppid,user,uid,group,gid,nlwp,pri,vsz,rss,etimes,systime,time,comm,args --ppid",
                parentPid);
        List<OSProcess> sorted = processSort(procs, limit, sort);
        return sorted.toArray(new OSProcess[sorted.size()]);
    }

    private List<OSProcess> getProcessListFromPS(String psCommand, int pid) {
        Map<Integer, String> cwdMap = LsofUtil.getCwdMap(pid);
        List<OSProcess> procs = new ArrayList<>();
        List<String> procList = ExecutingCommand.runNative(psCommand + (pid < 0 ? "" : pid));
        if (procList.isEmpty() || procList.size() < 2) {
            return procs;
        }
        // remove header row
        procList.remove(0);
        // Fill list
        for (String proc : procList) {
            String[] split = ParseUtil.whitespaces.split(proc.trim(), 16);
            // Elements should match ps command order
            if (split.length < 16) {
                continue;
            }
            long now = System.currentTimeMillis();
            OSProcess fproc = new OSProcess();
            switch (split[0].charAt(0)) {
            case 'R':
                fproc.setState(OSProcess.State.RUNNING);
                break;
            case 'I':
            case 'S':
                fproc.setState(OSProcess.State.SLEEPING);
                break;
            case 'D':
            case 'L':
            case 'U':
                fproc.setState(OSProcess.State.WAITING);
                break;
            case 'Z':
                fproc.setState(OSProcess.State.ZOMBIE);
                break;
            case 'T':
                fproc.setState(OSProcess.State.STOPPED);
                break;
            default:
                fproc.setState(OSProcess.State.OTHER);
                break;
            }
            fproc.setProcessID(ParseUtil.parseIntOrDefault(split[1], 0));
            fproc.setParentProcessID(ParseUtil.parseIntOrDefault(split[2], 0));
            fproc.setUser(split[3]);
            fproc.setUserID(split[4]);
            fproc.setGroup(split[5]);
            fproc.setGroupID(split[6]);
            fproc.setThreadCount(ParseUtil.parseIntOrDefault(split[7], 0));
            fproc.setPriority(ParseUtil.parseIntOrDefault(split[8], 0));
            // These are in KB, multiply
            fproc.setVirtualSize(ParseUtil.parseLongOrDefault(split[9], 0) * 1024);
            fproc.setResidentSetSize(ParseUtil.parseLongOrDefault(split[10], 0) * 1024);
            // Avoid divide by zero for processes up less than a second
            long elapsedTime = ParseUtil.parseDHMSOrDefault(split[11], 0L);
            fproc.setUpTime(elapsedTime < 1L ? 1L : elapsedTime);
            fproc.setStartTime(now - fproc.getUpTime());
            fproc.setKernelTime(ParseUtil.parseDHMSOrDefault(split[12], 0L));
            fproc.setUserTime(ParseUtil.parseDHMSOrDefault(split[13], 0L) - fproc.getKernelTime());
            fproc.setPath(split[14]);
            fproc.setName(fproc.getPath().substring(fproc.getPath().lastIndexOf('/') + 1));
            fproc.setCommandLine(split[15]);
            fproc.setCurrentWorkingDirectory(MapUtil.getOrDefault(cwdMap, fproc.getProcessID(), ""));
            // gets the open files count -- only do for single-PID requests
            if (pid >= 0) {
                List<String> openFilesList = ExecutingCommand.runNative(String.format("lsof -p %d", pid));
                fproc.setOpenFiles(openFilesList.size() - 1);
            }
            procs.add(fproc);
        }
        return procs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getProcessId() {
        return Libc.INSTANCE.getpid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getProcessCount() {
        List<String> procList = ExecutingCommand.runNative("ps -axo pid");
        if (!procList.isEmpty()) {
            // Subtract 1 for header
            return procList.size() - 1;
        }
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getThreadCount() {
        int threads = 0;
        for (String proc : ExecutingCommand.runNative("ps -axo nlwp")) {
            threads += ParseUtil.parseIntOrDefault(proc.trim(), 0);
        }
        return threads;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NetworkParams getNetworkParams() {
        return new FreeBsdNetworkParams();
    }

}
