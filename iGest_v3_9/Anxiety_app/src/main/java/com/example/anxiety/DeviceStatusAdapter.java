package com.example.anxiety;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.cardview.widget.CardView;
import java.util.List;

public class DeviceStatusAdapter extends RecyclerView.Adapter<DeviceStatusAdapter.DeviceStatusViewHolder> {

    private final List<DeviceStatus> deviceStatusList;
    private final Context context;
    private int selectedPosition = -1;
    private final DeviceTapListener listener;

    public interface DeviceTapListener {
        void onDeviceTapped(int position, DeviceStatus device);
    }

    public DeviceStatusAdapter(Context context, List<DeviceStatus> list, DeviceTapListener listener) {
        this.context = context;
        this.deviceStatusList = list;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceStatusViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_device_status, parent, false);
        return new DeviceStatusViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceStatusViewHolder holder, @SuppressLint("RecyclerView") int position) {
        DeviceStatus device = deviceStatusList.get(position);
        holder.viewStatusCircle.setBackgroundResource(device.isAnxious ? R.drawable.circle_red : R.drawable.circle_green);
        holder.editTextDeviceName.setText(device.displayName);
        holder.editTextDeviceName.setEnabled(device.isEditing);
        holder.imageViewEdit.setImageResource(device.isEditing ? android.R.drawable.ic_menu_save : R.drawable.ic_edit);

        // Card highlight for selected device
        if (position == selectedPosition) {
            ((CardView) holder.itemView).setCardBackgroundColor(context.getResources().getColor(R.color.primary));
            holder.editTextDeviceName.setTextColor(context.getResources().getColor(android.R.color.white));
        } else {
            ((CardView) holder.itemView).setCardBackgroundColor(context.getResources().getColor(android.R.color.white));
            holder.editTextDeviceName.setTextColor(context.getResources().getColor(android.R.color.black));
        }

        holder.imageViewEdit.setOnClickListener(v -> {
            device.isEditing = !device.isEditing;
            holder.editTextDeviceName.setEnabled(device.isEditing);
            if (device.isEditing) holder.editTextDeviceName.requestFocus();
            notifyItemChanged(position);
        });
        holder.editTextDeviceName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) {}
            @Override
            public void afterTextChanged(Editable s) { device.displayName = s.toString(); }
        });
        holder.itemView.setOnClickListener(v -> {
            int prev = selectedPosition;
            selectedPosition = position;
            notifyItemChanged(prev);
            notifyItemChanged(selectedPosition);
            if (listener != null) listener.onDeviceTapped(position, device);
        });
    }

    @Override
    public int getItemCount() { return deviceStatusList.size(); }

    public void updateStatus(String deviceKey, boolean isAnxious) {
        for (int i = 0; i < deviceStatusList.size(); i++) {
            DeviceStatus device = deviceStatusList.get(i);
            if (device.deviceKey.equals(deviceKey)) {
                device.isAnxious = isAnxious;
                notifyItemChanged(i);
                break;
            }
        }
    }

    public DeviceStatus getSelectedDevice() {
        if (selectedPosition >= 0 && selectedPosition < deviceStatusList.size())
            return deviceStatusList.get(selectedPosition);
        return null;
    }

    public static class DeviceStatusViewHolder extends RecyclerView.ViewHolder {
        View viewStatusCircle;
        EditText editTextDeviceName;
        ImageView imageViewEdit;
        public DeviceStatusViewHolder(@NonNull View itemView) {
            super(itemView);
            viewStatusCircle = itemView.findViewById(R.id.viewStatusCircle);
            editTextDeviceName = itemView.findViewById(R.id.editTextDeviceName);
            imageViewEdit = itemView.findViewById(R.id.imageViewEdit);
        }
    }
}