/*
 * Copyright (C) 2010 Keith Kildare
 * 
 * This file is part of SimplyDo.
 * 
 * SimplyDo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * SimplyDo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with SimplyDo.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package kdk.android.simplydo;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.util.Log;

/**
 * A cache of the database data which does the actual db calls lazily on
 * a separate thread.
 */
public class CachingDataViewer implements DataViewer
{
    private DataManager dataManager;
    
    private List<ItemDesc> itemData = new ArrayList<ItemDesc>();
    private List<ListDesc> listData = new ArrayList<ListDesc>();
    
    private Thread dbUpdateThread;
    private Object viewerLock = new Object();
    private LinkedList<ViewerTask> taskQueue = new LinkedList<ViewerTask>();
    private boolean running = false;
    private boolean interruptRequire = false;
    private ListDesc selectedList;

    
    public CachingDataViewer(DataManager dataManager)
    {
        this.dataManager = dataManager;
        
        dbUpdateThread = new Thread(new Runnable() {
            @Override
            public void run()
            {
                dbUpdateLoop();
            }
        }, "DB Update");
    }
    
    
    public void start()
    {
        running = true;
        // start task queue
        
        dbUpdateThread.start();
    }
    
    
    public void close()
    {
        Log.d(L.TAG, "Entered CachingDataView.close()");
        synchronized (viewerLock)
        {
            flushTasksNoLock();
            
            running = false;
            
            if(interruptRequire)
            {
                Log.d(L.TAG, "Close interrupt required");
                dbUpdateThread.interrupt();
            }
        }        
        
        try
        {
            dbUpdateThread.join();
        }
        catch (InterruptedException e)
        {
            Log.d(L.TAG, "shutdown join interrupted", e);
        }
        
        Log.d(L.TAG, "Exited CachingDataView.close()");
    }


    public List<ItemDesc> getItemData()
    {
        synchronized (viewerLock)
        {
            return itemData;
        }
    }

    
    public List<ListDesc> getListData()
    {
        synchronized (viewerLock)
        {
            return listData;
        }
    }
    
    
    @Override
    public ListDesc getSelectedList()
    {
        return selectedList;
    }


    @Override
    public void setSelectedList(ListDesc selectedList)
    {
        flushTasks();
        
        // this is ok since this thread is the only source
        // of task and we've just flushed the task queue
        if(selectedList != null)
        {
            itemData = dataManager.fetchItems(selectedList.getId());
        }
        else
        {
            itemData.clear();
        }
        this.selectedList = selectedList;
    }


    public void fetchLists()
    {
        Log.v(L.TAG, "Entered fetchLists()");
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.FETCH_LISTS;
        doTaskAndWait(task);
        Log.v(L.TAG, "Exited fetchLists()");
    }

    
    public void fetchItems(int listId)
    {
        Log.v(L.TAG, "Entered fetchItems()");
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.FETCH_ITEMS;
        task.args = new Object[]{listId};
        doTaskAndWait(task);
        Log.v(L.TAG, "Exited fetchItems()");
    }
    
    

    @Override
    public void createItem(String label)
    {
        if(selectedList == null)
        {
            Log.e(L.TAG, "createItem() called but no list is selected");
            return;
        }
        
        int listId = selectedList.getId();
        
        ViewerTask createTask = new ViewerTask();
        createTask.taskId = ViewerTask.CREATE_ITEM;
        createTask.args = new Object[]{listId, label};
        
        ViewerTask fetchTask = new ViewerTask();
        fetchTask.taskId = ViewerTask.FETCH_ITEMS;
        fetchTask.args = new Object[]{listId};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(createTask);
            taskQueue.add(fetchTask);
            viewerLock.notifyAll();
            
            flushTasksNoLock();
            
            updateListStats();
        }
    }


    @Override
    public void createList(String label)
    {
        ViewerTask createTask = new ViewerTask();
        createTask.taskId = ViewerTask.CREATE_LIST;
        createTask.args = new Object[]{label};

        ViewerTask fetchTask = new ViewerTask();
        fetchTask.taskId = ViewerTask.FETCH_LISTS;
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(createTask);
            taskQueue.add(fetchTask);
            viewerLock.notifyAll();
            
            flushTasksNoLock();
        }
    }


    @Override
    public void deleteInactive()
    {
        if(selectedList == null)
        {
            Log.e(L.TAG, "deleteInactive() called but no list is selected");
            return;
        }
        
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.DELETE_INACTIVE;
        task.args = new Object[]{selectedList.getId()};

        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            List<ItemDesc> toDelete = new ArrayList<ItemDesc>();
            for(ItemDesc i : itemData)
            {
                if(!i.isActive())
                {
                    toDelete.add(i);
                }
            }
            itemData.removeAll(toDelete);
            
            updateListStats();
        }
    }


    @Override
    public void deleteItem(int itemId)
    {
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.DELETE_ITEM;
        task.args = new Object[]{itemId};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            ItemDesc delete = null;
            for(ItemDesc i : itemData)
            {
                if(itemId == i.getId())
                {
                    delete = i;
                    break;
                }
            }
            itemData.remove(delete);
            updateListStats();
        }
    }


    @Override
    public void deleteList(int listId)
    {
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.DELETE_LIST;
        task.args = new Object[]{listId};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            ListDesc delete = null;
            for(ListDesc i : listData)
            {
                if(listId == i.getId())
                {
                    delete = i;
                    break;
                }
            }
            listData.remove(delete);
        }
        
    }


    @Override
    public void updateItemLabel(int itemId, String newLabel)
    {
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.UPDATE_ITEM_LABEL;
        task.args = new Object[]{itemId, newLabel};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            for(ItemDesc i : itemData)
            {
                if(itemId == i.getId())
                {
                    i.setLabel(newLabel);
                    break;
                }
            }
        }        
    }


    @Override
    public void updateListLabel(int listId, String newLabel)
    {
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.UPDATE_LIST_LABEL;
        task.args = new Object[]{listId, newLabel};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            for(ListDesc i : listData)
            {
                if(listId == i.getId())
                {
                    i.setLabel(newLabel);
                    break;
                }
            }
        }        
    }
    

    public void updateItemActiveness(int itemId, boolean active)
    {
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.UPDATE_ACTIVENESS;
        task.args = new Object[]{itemId, active};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            for(ItemDesc i : itemData)
            {
                if(itemId == i.getId())
                {
                    i.setActive(active);
                    break;
                }
            }
            
            // update lists data
            if(selectedList != null)
            {
                int inactive = selectedList.getActiveItems();
                inactive += active?1:-1;
                selectedList.setActiveItems(inactive);
            }
        }
    }
    

    public void updateItemStarness(int itemId, boolean star)
    {
        ViewerTask task = new ViewerTask();
        task.taskId = ViewerTask.UPDATE_STARNESS;
        task.args = new Object[]{itemId, star};
        
        synchronized (viewerLock)
        {
            // queue fetch lists
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            // update items data
            for(ItemDesc i : itemData)
            {
                if(itemId == i.getId())
                {
                    i.setStar(star);
                    break;
                }
            }
        }
    }
    
    
    private void flushTasksNoLock()
    {
        while(taskQueue.size() != 0)
        {
            try
            {
                viewerLock.wait(300);
            }
            catch (InterruptedException e)
            {
                Log.e(L.TAG, "Exception waiting for flushTasks()", e);
            }
        }
    }
    
    private void flushTasks()
    {
        synchronized (viewerLock)
        {
            flushTasksNoLock();
        }
    }
    
    private void updateListStats()
    {
        selectedList.setTotalItems(itemData.size());
        int active = 0;
        for(ItemDesc item : itemData)
        {
            if(item.isActive())
            {
                active++;
            }
        }
        selectedList.setActiveItems(active);
    }
    
    private void doTaskAndWait(ViewerTask task)
    {
        synchronized (viewerLock)
        {
            taskQueue.add(task);
            viewerLock.notifyAll();
            
            try
            {
                while(!task.done)
                {
                    viewerLock.wait();
                }
            }
            catch(InterruptedException e)
            {
                Log.e(L.TAG, "Error waiting for task", e);
            }
        }
    }
    
    
    private void dbUpdateLoop()
    {
        Log.v(L.TAG, "Entered dbUpdateLoop()");
        while(running)
        {
            try
            {
                // get task
                ViewerTask task;
                synchronized (viewerLock)
                {
                    while(taskQueue.size() == 0)
                    {
                        interruptRequire = true;
                        viewerLock.wait();
                        interruptRequire = false;
                    }
                    task = taskQueue.peek();
                }
                
                boolean doNotify = true;
                try
                {
                    // do it
                    switch(task.taskId)
                    {
                    case ViewerTask.FETCH_LISTS:
                    {
                        List<ListDesc> lists = dataManager.fetchLists();
                        synchronized (viewerLock)
                        {
                            listData = lists;
                            task.done = true;
                            viewerLock.notifyAll();
                        }
                        doNotify = false;
                        break;
                    }
                    case ViewerTask.FETCH_ITEMS:
                    {
                        List<ItemDesc> items = dataManager.fetchItems((Integer)task.args[0]);
                        synchronized (viewerLock)
                        {
                            itemData = items;
                            task.done = true;
                            viewerLock.notifyAll();
                        }
                        doNotify = false;
                        break;
                    }
                    case ViewerTask.UPDATE_ACTIVENESS:
                    {
                        dataManager.updateItemActiveness((Integer)task.args[0], (Boolean)task.args[1]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.UPDATE_STARNESS:
                    {
                        dataManager.updateItemStarness((Integer)task.args[0], (Boolean)task.args[1]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.UPDATE_ITEM_LABEL:
                    {
                        dataManager.updateItemLabel((Integer)task.args[0], (String)task.args[1]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.UPDATE_LIST_LABEL:
                    {
                        dataManager.updateListLabel((Integer)task.args[0], (String)task.args[1]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.DELETE_LIST:
                    {
                        dataManager.deleteList((Integer)task.args[0]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.DELETE_ITEM:
                    {
                        dataManager.deleteItem((Integer)task.args[0]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.DELETE_INACTIVE:
                    {
                        dataManager.deleteInactive((Integer)task.args[0]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.CREATE_LIST:
                    {
                        dataManager.createList((String)task.args[0]);
                        task.done = true;
                        break;
                    }
                    case ViewerTask.CREATE_ITEM:
                    {
                        dataManager.createItem((Integer)task.args[0], (String)task.args[1]);
                        task.done = true;
                        break;
                    }
                    default:
                        Log.w(L.TAG, "Unknown task enumeration " + task.taskId);
                    }
                }
                finally
                {
                    synchronized (viewerLock)
                    {
                        taskQueue.remove();                
                        if(doNotify)
                        {
                            viewerLock.notifyAll();
                        }
                    }
                }                
            }
            catch(InterruptedException e)
            {
                if(running)
                {
                    Log.e(L.TAG, "Exception in DB update loop", e);
                }
                else
                {
                    Log.d(L.TAG, "dbUpdateLoop() interrupt exit");
                }
            }
            catch(Exception e)
            {
                Log.e(L.TAG, "Exception in DB update loop", e);
            }
        }
        Log.v(L.TAG, "Exited dbUpdateLoop()");
    }
    
    
    private static class ViewerTask
    {
        private static final int FETCH_LISTS = 0;
        private static final int FETCH_ITEMS = 1;
        private static final int UPDATE_ACTIVENESS = 2;
        private static final int UPDATE_ITEM_LABEL = 3;
        private static final int UPDATE_LIST_LABEL = 4;
        private static final int DELETE_LIST = 5;
        private static final int DELETE_ITEM = 6;
        private static final int DELETE_INACTIVE = 7;
        private static final int CREATE_LIST = 8;
        private static final int CREATE_ITEM = 9;
        private static final int UPDATE_STARNESS = 10;
        
        private int taskId;
        private Object[] args;
        private boolean done = false;
    }

}