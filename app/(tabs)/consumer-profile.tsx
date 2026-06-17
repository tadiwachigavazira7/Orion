// app/(tabs)/consumer-profile.tsx
import React from 'react';
import { SafeAreaView, StyleSheet } from 'react-native';
import ConsumerProfileScreen from '../../src/features/consumer/consumerprofile/screens/consumerprofilescreen';

export default function TabProfileRoute() {
  return (
    <SafeAreaView style={styles.safeArea}>
      <ConsumerProfileScreen />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: 'white',
  },
});