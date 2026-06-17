import { Ionicons } from '@expo/vector-icons';
import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

export function ZipCodeSelector() {
  const [zipCode, setZipCode] = useState('28031'); // Default placeholder example
  const [isEditing, setIsEditing] = useState(false);

  const handleLocationPress = () => {
    // This is where your native device location services logic will hook in later!
    console.log('Requesting core hardware GPS tracking coordinates...');
    setIsEditing(true);
  };

  return (
    <View style={styles.container}>
      <Pressable onPress={handleLocationPress} style={styles.row}>
        {/* Standard location pin (teardrop marker) */}
        <Ionicons name="location-sharp" size={16} color="black" style={styles.pinIcon} />
        
        {isEditing ? (
          <TextInput
            style={styles.zipInput}
            value={zipCode}
            onChangeText={setZipCode}
            keyboardType="number-pad"
            maxLength={5}
            autoFocus
            onBlur={() => setIsEditing(false)}
            onSubmitEditing={() => setIsEditing(false)}
          />
        ) : (
          <Text style={styles.zipText}>{zipCode || 'Enter Zip'}</Text>
        )}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignSelf: 'flex-start', // Keeps the button tightly wrapping its content on the left
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#F3F3F3', // Light neutral button block
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 20,
    borderWidth: 1.5,
    borderColor: 'black',
  },
  pinIcon: {
    fontSize: 14,
    marginRight: 4,
  },
  zipText: {
    fontSize: 14,
    fontWeight: '600',
    color: 'black',
    fontFamily: 'serif',
  },
  zipInput: {
    fontSize: 14,
    fontWeight: '600',
    color: 'black',
    fontFamily: 'serif',
    padding: 0,
    width: 45,
  },
});